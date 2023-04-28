/*
 * Copyright (C) Bigly Software, Inc, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA
 */

package org.parg.azureus.plugins.networks.i2p.proxydht;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import org.parg.azureus.plugins.networks.i2p.I2PHelperAltNetHandlerTor;
import org.parg.azureus.plugins.networks.i2p.I2PHelperPlugin;

import com.biglybt.core.dht.transport.DHTTransportAlternativeContact;
import com.biglybt.core.dht.transport.DHTTransportAlternativeNetwork;
import com.biglybt.core.dht.transport.udp.impl.DHTUDPUtils;
import com.biglybt.core.util.AENetworkClassifier;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.AddressUtils;
import com.biglybt.core.util.BDecoder;
import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.CopyOnWriteList;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.pif.PluginAdapter;
import com.biglybt.pif.messaging.MessageException;
import com.biglybt.pif.messaging.MessageManager;
import com.biglybt.pif.messaging.generic.GenericMessageConnection;
import com.biglybt.pif.messaging.generic.GenericMessageConnectionListener;
import com.biglybt.pif.messaging.generic.GenericMessageEndpoint;
import com.biglybt.pif.messaging.generic.GenericMessageHandler;
import com.biglybt.pif.messaging.generic.GenericMessageRegistration;
import com.biglybt.pif.messaging.generic.GenericMessageConnection.GenericMessageConnectionPropertyHandler;
import com.biglybt.pif.network.ConnectionManager;
import com.biglybt.pif.network.RateLimiter;
import com.biglybt.pif.utils.PooledByteBuffer;

import net.i2p.data.Base32;


public class 
TorProxyDHT
{
	private static final boolean	LOOPBACK			= false;
	private static final boolean	ENABLE_LOGGING		= true;
	
	private static final boolean	ENABLE_PROXY_CLIENT = true;
	private static final boolean	ENABLE_PROXY_SERVER = true;
	
	private I2PHelperPlugin		plugin;
	
	private static final int MT_PROXY_KEEP_ALIVE		= 0;
	private static final int MT_PROXY_ALLOC_REQUEST		= 1;
	private static final int MT_PROXY_ALLOC_OK_REPLY	= 2;
	private static final int MT_PROXY_ALLOC_FAIL_REPLY	= 3;
	private static final int MT_PROXY_PROBE_REQUEST		= 4;
	private static final int MT_PROXY_PROBE_REPLY		= 5;
	private static final int MT_PROXY_CLOSE				= 6;
	private static final int MT_PROXY_OP_REQUEST		= 7;
	private static final int MT_PROXY_OP_REPLY			= 8;

	private static final int PROXY_OP_PUT		= 1;
	private static final int PROXY_OP_GET		= 2;
	
	private static final int MAX_SERVER_PROXIES	= 4;
	
	private final String instance_id;
	
	private final RateLimiter inbound_limiter;
	
	private I2PHelperPlugin.TorEndpoint tep_mix;
	private I2PHelperPlugin.TorEndpoint tep_pure;

	private GenericMessageRegistration msg_registration;
	
	private CopyOnWriteList<Connection>		connections = new CopyOnWriteList<>();

	private volatile OutboundConnectionProxy	current_client_proxy;
	
	private CopyOnWriteList<InboundConnectionProxy>	server_proxies = new CopyOnWriteList<>();
	
	private boolean	checking_client_proxy;
	private boolean client_proxy_check_outstanding;

	private InetSocketAddress failed_client_proxy;
	
	private int failed_client_proxy_retry_count = 0;
	
	private int PROXY_FAIL_MAX	= 256;
	
	private volatile int proxy_client_consec_fail_count;
	
	private Map<String,String>		proxy_client_fail_map = 
		new LinkedHashMap<String,String>(PROXY_FAIL_MAX,0.75f,true)
		{
			@Override
			protected boolean
			removeEldestEntry(
		   		Map.Entry<String,String> eldest) 
			{
				return size() > PROXY_FAIL_MAX;
			}
		};
		
	private int PROXY_BACKUP_MAX	= 64;
		
	private Map<InetSocketAddress,String>		proxy_client_backup_map = 
		new LinkedHashMap<InetSocketAddress,String>(PROXY_BACKUP_MAX,0.75f,true)
		{
			@Override
			protected boolean
			removeEldestEntry(
		   		Map.Entry<InetSocketAddress,String> eldest) 
			{
				return size() > PROXY_BACKUP_MAX;
			}
		};

	private AtomicLong						proxy_request_seq	= new AtomicLong();
	private LinkedList<ProxyRequest>		proxy_requests		= new LinkedList<>();
	private AESemaphore						proxy_requests_sem	= new AESemaphore( "TPD:req" );
	private AEThread2						proxy_request_dispatcher;
	
	private OutboundConnectionProxy			active_client_proxy;
	
	private int PROXY_FAIL_UIDS_MAX	= 8;
	
	private Map<String,String>		proxy_client_fail_uids = 
		new LinkedHashMap<String,String>(PROXY_FAIL_UIDS_MAX,0.75f,true)
		{
			@Override
			protected boolean
			removeEldestEntry(
		   		Map.Entry<String,String> eldest) 
			{
				return size() > PROXY_FAIL_UIDS_MAX;
			}
		};
		
	private volatile boolean destroyed;
	
	public
	TorProxyDHT(
		I2PHelperPlugin	_plugin )
	{
		plugin = _plugin;
		
		byte[] temp = new byte[8];
		
		RandomUtils.nextSecureBytes( temp );
		
		instance_id = Base32.encode( temp );
		
		ConnectionManager cman = plugin.getPluginInterface().getConnectionManager();

		int inbound_limit = 50*1024;

		inbound_limiter 	= cman.createRateLimiter( "TorDHTProxy:in", inbound_limit );
		
		plugin.getPluginInterface().addListener(
			new PluginAdapter()
			{
				@Override
				public void 
				closedownInitiated()
				{
					for ( Connection con: connections ){
						
						con.closingDown();
					}
				}
			});
	}
	
	public void
	initialise()
	{		
		tep_mix		= plugin.getTorEndpoint( 0 );
		tep_pure	= plugin.getTorEndpoint( 1 );
		
		try{
			int mix_port = tep_mix.getPort();
			
			msg_registration =
					plugin.getPluginInterface().getMessageManager().registerGenericMessageType(
						"TorProxyDHT",
						"TorProxyDHT Registration",
						MessageManager.STREAM_ENCRYPTION_NONE,
						new GenericMessageHandler()
						{
							@Override
							public boolean
							accept(
								GenericMessageConnection	gmc )
	
								throws MessageException
							{
								InetSocketAddress originator = gmc.getEndpoint().getNotionalAddress();
								
								if ( AENetworkClassifier.categoriseAddress( AddressUtils.getHostAddress( originator)) != AENetworkClassifier.AT_TOR ){
									
									gmc.close();
									
									return( false );	
								}							
								
								if ( originator.getPort() == mix_port ){
									
									new InboundConnectionProxy( gmc );
									
								}else{
									
									new InboundConnectionProbe( gmc );
								}
								
								return( true );
							}
						});
				
				SimpleTimer.addPeriodicEvent(
					"TorProxyDHT",
					10*1000,
					new TimerEventPerformer()
					{
						int tick_count = -1;
						
						@Override
						public void 
						perform(
							TimerEvent event)
						{
							tick_count++;
							
							long now = SystemTime.getMonotonousTime();
							
							if ( tick_count % 6 == 0 ){
								
								OutboundConnectionProxy ccp = current_client_proxy;
								
								String ccp_str;
								
								if ( ccp == null ){
									
									ccp_str = "";
									
								}else{
									
									ccp_str = ", " + ccp.getString();
								}
								
								log( "con=" + connections.size() + ccp_str );
								
								for ( InboundConnection sp: server_proxies ){
									
									log( "    " + sp.getString());
								}
							}
							
							checkClientProxy( true );
							
							if ( tick_count % 3 == 0 ){
								
								checkServerProxies();
								
								for ( Connection con: connections ){
										
									con.timerTick( now );
									
									if ( 	con != current_client_proxy && 
											!( con instanceof InboundConnectionProxy && server_proxies.contains((InboundConnectionProxy)con ))){
										
										if ( con.getAgeSeconds( now ) > 120 ){
											
											con.failed( new Exception( "Dead connection" ));
										}
									}
								}
							}
						}
					});
				
			checkClientProxy( true );
			
			byte[] torrent_hash = new byte[]{ 0,1,2,3,4,5 };
				
			proxyAnnounce( torrent_hash, true );
			
		}catch( Throwable e ){
			
			Debug.out( e );
		}
	}
	
	private void
	proxyAnnounce(
		byte[]		torrent_hash,
		boolean		is_seed )
	
		throws Exception
	{
		MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
					
		sha256.update( "TorProxyDHT::torrent_hash".getBytes( Constants.UTF_8 ));
		
		sha256.update( torrent_hash );
		
		byte[] key = sha256.digest();
			
		Map payload = new HashMap<>();
		
		payload.put( "s", is_seed?1:0 );
					
		proxyPut( key, payload );
	}
	
	public void
	proxyPut(
		byte[]			key,
		byte[]			value )
	
		throws Exception
	{
		Map map = new HashMap<>();
		
		map.put( "v", value );
		
		proxyPut( key, map );
	}

	
	public void
	proxyPut(
		byte[]			key,
		Map				value )
	
		throws Exception
	{
			// "h", "p" and "z" are reserved
		
		if ( value.containsKey("h") || value.containsKey("p")|| value.containsKey( "z" )){
			
			throw( new Exception( "Invalid map, uses reserved keys" ));
		}
		
		addRequest( new ProxyRequestPut( key, value ));		
	}
	
	public void
	proxyGet(
		byte[]			key )
	{
		addRequest( new ProxyRequestGet( key ));		
	}
	
	private void
	addRequest(
		ProxyRequest		request )
	{
		synchronized( proxy_requests ){
			
			Iterator<ProxyRequest> it = proxy_requests.iterator();
			
			while( it.hasNext()){
				
				ProxyRequest r = it.next();
				
				if ( r.getKey().equals( request.getKey())){
					
					it.remove();
				}
			}
			
			proxy_requests.add( request );
			
			proxy_requests_sem.release();
			
			checkRequestDispatcher();
		}
	}
	
	private void
	requestComplete(
		ProxyRequest		request )
	{
		System.out.println( "request completed" );
		
		if ( request.getType() == ProxyRequest.RT_GET ){
			
			List<Map> values = ((ProxyRequestGet)request).getValues();
			
			for ( Map v: values ){
				
				v.remove( "h" );
				
				System.out.println( "    " + BDecoder.decodeStrings(v));
			}
		}
	}
	
	private void
	requestFailed(
		ProxyRequest		request )
	{
		Debug.out( "need to reschedule request" );
	}
	
	private void
	checkRequestDispatcher()
	{
		if ( proxy_request_dispatcher == null ){
			
			if ( active_client_proxy != null ){
				
				proxy_request_dispatcher = 
					new AEThread2( "TPD:rd" )
					{
						public void 
						run()
						{
							while( true ){
								
								if ( !proxy_requests_sem.reserve( 10*1000)){
									
									synchronized( proxy_requests ){
										
										if ( proxy_requests.isEmpty()){
											
											if ( proxy_request_dispatcher == this ){
												
												proxy_request_dispatcher = null;
												
												return;
											}
										}
									}
									
									continue;
								}
								
								OutboundConnectionProxy	proxy;
								
								ProxyRequest 			request;
								
								synchronized( proxy_requests ){
									
									proxy = active_client_proxy;

									if (	proxy == null ||
											proxy.getState() != OutboundConnectionProxy.STATE_ACTIVE ||
											proxy_request_dispatcher != this ){
										
										proxy_requests_sem.release();
										
										break;
									}
																		
									if ( proxy_requests.isEmpty()){
										
										request = null;
										
									}else{
										
										request = proxy_requests.removeLast();
									}
								}
								
								if ( request != null ){
									
									proxy.addRequest( 
										request,
										new ProxyRequestListener()
										{
											boolean done = false;
											
											@Override
											public void 
											complete(
												ProxyRequest		request )
											{
												synchronized( this ){
													if ( done ){
														return;
													}
													done = true;
												}
												requestComplete( request );
											}
											
											@Override
											public void 
											failed(
												ProxyRequest		request )
											{
												synchronized( this ){
													if ( done ){
														return;
													}
													done = true;
												}
												requestFailed( request );
											}
										});
								}
							}
						}
					};
				
				proxy_request_dispatcher.start();
			}
		}else{
			
			if ( active_client_proxy == null ){
				
				proxy_requests_sem.release();
				
				proxy_request_dispatcher = null;
			}
		}
	}
	
	private void
	maskValue(
		byte[]		torrent_hash,
		byte[]		masked_value )
	
		throws Exception
	{
		int	pos = 0;
		
		MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
		
		for ( int i=0;pos<masked_value.length;i++){
			
			sha256.update( "TorProxyDHT::mask".getBytes( Constants.UTF_8 ));
			
			sha256.update((byte)i);
			
			sha256.update( torrent_hash );
			
			byte[] value_mask = sha256.digest();
			
			for ( int m=0;pos<masked_value.length&&m<value_mask.length;pos++,m++){
				
				masked_value[pos] ^= value_mask[m];
			}
		}
	}
	
	private Map<String,byte[]> fake_dht = new HashMap<>();
	
	private void
	proxyDHTPut(
		byte[]		key,
		byte[]		value )
	{
		System.out.println( "DHT PUT" );
		
		fake_dht.put( Base32.encode( key ), value );
	}
	
	private List<byte[]>
	proxyDHTGet(
		byte[]		key )
	{
		System.out.println( "DHT GET" );
		
		List<byte[]> values = new ArrayList<>();
		
		byte[] value = fake_dht.get( Base32.encode( key ));
		
		if ( value != null ){
			
			values.add( value );
		}
		
		return( values );
	}
	
	private void
	addBackupContacts(
		List<InetSocketAddress>		isas )
	{
		List<InetSocketAddress>		to_add = new ArrayList<>();
		
		synchronized( proxy_client_fail_map ){
		
			for ( InetSocketAddress isa: isas ){
				
				if ( !proxy_client_fail_map.containsKey( AddressUtils.getHostAddress(isa))){
					
					to_add.add( isa );
				}
			}
		}
		
		synchronized( proxy_client_backup_map ){
			
			for ( InetSocketAddress isa: to_add ){
				
				proxy_client_backup_map.put( isa, "" );
			}
		}
	}
		
	private void
	checkClientProxy(
		boolean 	force )
	{
		if ( destroyed || !ENABLE_PROXY_CLIENT ){
			
			return;
		}
		
		if ( proxy_client_consec_fail_count > 10 && !force ){
			
				// fall back to periodic attempts rather than immediate retries
			
			return;
		}
		
		synchronized( connections ){
			
			if ( current_client_proxy != null && !current_client_proxy.isClosed()){
				
				return;
			}
			
			if ( checking_client_proxy ){
				
				client_proxy_check_outstanding = true;
				
				return;
			}
			
			checking_client_proxy = true;
		}
		
		AEThread2.createAndStartDaemon( "ProxyClientCheck", ()->{
										
				try{
					checkClientProxySupport();
					
				}finally{
					
					boolean recheck = false;
					
					synchronized( connections ){
						
						checking_client_proxy = false;
						
						if ( client_proxy_check_outstanding ){
							
							client_proxy_check_outstanding = false;
							
							recheck = true;
						}
					}
					
					if ( recheck ){
						
						checkClientProxy( false );
					}
				}
		});
	}
	
	private void
	checkServerProxies()
	{
		if ( destroyed ){
			
			return;
		}
		
			// just in case
		
		for ( InboundConnectionProxy sp: server_proxies ){
			
			if ( sp.isClosed()){
				
				server_proxies.remove( sp);
			}
		}
	}
	
	private boolean
	checkClientProxySupport()
	{
		
		String local_mix_host	= tep_mix.getHost();
		String local_pure_host	= tep_pure.getHost();
		
		if ( local_mix_host == null || local_pure_host == null ){
			
			return( false );
		}
		
		InetSocketAddress retry_address = null;
		
		synchronized( proxy_client_fail_map ){
		
			if ( failed_client_proxy != null ){
				
				if ( failed_client_proxy_retry_count < 3 ){
					
					retry_address = failed_client_proxy;
					
					proxy_client_fail_map.remove( AddressUtils.getHostAddress( retry_address ));

					failed_client_proxy_retry_count++;
					
				}else{
					
					failed_client_proxy				= null;
					failed_client_proxy_retry_count	= 0;
				}
			}
		}
		
		if ( retry_address != null ){
			
			log( "Retrying client proxy connection" );
			
			if ( tryClientProxy( local_mix_host, retry_address )){
				
				return( true );
			}
		}
		
		DHTTransportAlternativeNetwork net = DHTUDPUtils.getAlternativeNetwork( DHTTransportAlternativeNetwork.AT_TOR );
		
		if ( net == null ){
			
			return( false );
		}
		
		List<DHTTransportAlternativeContact> contacts = DHTUDPUtils.getAlternativeContacts( DHTTransportAlternativeNetwork.AT_TOR, 128 );

		Collections.shuffle( contacts );
		
		for ( DHTTransportAlternativeContact contact: contacts ){
		
			InetSocketAddress target = net.getNotionalAddress( contact );
			
			if ( target == null ){
				
				continue;
			}

			if ( tryClientProxy( local_mix_host, target )){
				
				return( true );
			}
		}
		
		List<InetSocketAddress> backups = null;
		
		synchronized( proxy_client_backup_map ){
			
			if ( !proxy_client_backup_map.isEmpty()){
			
				backups = new ArrayList<>( proxy_client_backup_map.keySet());
				
				proxy_client_backup_map.clear();
			}
		}
		
		if ( backups != null ){
			
			Iterator<InetSocketAddress> it = backups.iterator();
			
			while( it.hasNext()){
				
				InetSocketAddress target = it.next();
				
				it.remove();
				
				if ( tryClientProxy( local_mix_host, target )){
					
					synchronized( proxy_client_backup_map ){
						
						while( it.hasNext()){
							
							proxy_client_backup_map.put( it.next(), "" );
						}
					}
					
					return( true );
				}
			}
		}
		
			// nothing found, reset
		
		synchronized( proxy_client_fail_map ){
			
			proxy_client_fail_map.clear();
		}
		
		return( false );
	}
	
	boolean
	tryClientProxy(
		String				local_mix_host,
		InetSocketAddress	target )
	{
		String target_host = AddressUtils.getHostAddress(target);
		
		if ( AENetworkClassifier.categoriseAddress( target_host ) != AENetworkClassifier.AT_TOR ){
			
			return( false );
		}
		
		if ( local_mix_host.equals( target_host )){
							
			return( false );
		}
		
		synchronized( proxy_client_fail_map ){
			
			if ( proxy_client_fail_map.containsKey( target_host )){
				
				return( false );
			}
			
				// preemptively add it, we'll remove it if it succeeds
			
			proxy_client_consec_fail_count++;
			
			proxy_client_fail_map.put( target_host, "" );
		}
		
		try{	
			if ( LOOPBACK ){
			
				target = InetSocketAddress.createUnresolved( "umklbodffjt4jhjm7ysfoej3nctmtm7yatvbopq2lo32x45am3siddqd.onion", 27657);
				// target = InetSocketAddress.createUnresolved( "nhrtp6h2o7puwq5ce45f3dfvszal3jeq4d5b3lgjxi72k2x5pspz5mad.onion", 27657);
			}
			
			log( "Trying proxy " + target );

			new OutboundConnectionProxy( target );
			
			return( true );
			
		}catch( Throwable e ){
			
		}
		
		return( false );
	}
	
	private void
	proxyClientSetupComplete(
		OutboundConnectionProxy		proxy,
		String						instance_id )
	{
		synchronized( proxy_client_fail_map ){
			
			proxy_client_consec_fail_count = 0;
			
			proxy_client_fail_map.remove( proxy.getHost());
			
			failed_client_proxy = null;
			
			failed_client_proxy_retry_count = 0;
		}
		
		synchronized( proxy_requests ){
		
			if ( !proxy_client_fail_uids.containsKey( proxy.getUID())){
				
				active_client_proxy = proxy;
				
				checkRequestDispatcher();
			}
		}
	}
	
	private void
	proxyClientFailed(
		OutboundConnectionProxy		proxy )
	{
		synchronized( proxy_requests ){
	
			proxy_client_fail_uids.put( proxy.getUID(), "" );
			
			if ( active_client_proxy == proxy ){
				
				active_client_proxy = null;
			}
			
			checkRequestDispatcher();
		}
	}
	
	private void
	proxyServerSetupComplete(
		InboundConnectionProxy	proxy,
		String					instance_id )
	{
		
	}
	
	private void
	proxyServerFailed(
		InboundConnectionProxy	proxy )
	{
		
	}
	
	private void
	addConnection(
		Connection		connection )
	{
		OutboundConnectionProxy old_proxy = null;
		
		synchronized( connections ){
		
			connections.add( connection );
			
			if ( connection instanceof OutboundConnectionProxy ){
				
				if ( current_client_proxy != null && current_client_proxy != connection ){
					
					old_proxy = current_client_proxy;
				}
				
				current_client_proxy = (OutboundConnectionProxy)connection;
			}
		}
		
		if ( old_proxy != null ){
			
			old_proxy.close();
		}
	}
	
	private void
	removeConnection(
		Connection		connection )
	{
		boolean was_client_proxy;
		
		boolean was_server_proxy;
		
		synchronized( connections ){
		
			connections.remove( connection );
			
			was_client_proxy = connection == current_client_proxy;
			
			if ( was_client_proxy ){
				
				current_client_proxy = null;
			}
			
			if ( connection instanceof InboundConnectionProxy ){
			
				was_server_proxy = server_proxies.remove((InboundConnectionProxy)connection );
				
			}else{
				
				was_server_proxy = false;
			}
		}
		
		if ( was_client_proxy ){
			
			OutboundConnectionProxy cp = (OutboundConnectionProxy)connection;
			
			proxyClientFailed( cp );
			
			InetSocketAddress ias = cp.getAddress();
			
			if ( cp.hasBeenActive()){
				
				synchronized( proxy_client_fail_map ){
					
					if ( failed_client_proxy == null || !failed_client_proxy.equals( ias )){
						
						failed_client_proxy = ias;
						
						failed_client_proxy_retry_count = 0;
					}
				}
				
				checkClientProxy( true );
				
			}else{
				
				checkClientProxy( false );
			}
		}
		
		if ( was_server_proxy ){
			
			proxyServerFailed(( InboundConnectionProxy) connection );
		}
	}
	
	public void
	destroy()
	{
		destroyed = true;
		
		if ( msg_registration != null ){
			
			msg_registration.cancel();
			
			msg_registration = null;
		}
		
		for ( Connection con: connections ){
			
			con.close();
		}
	}
	
	private void
	log(
		String		str )
	{
		if ( ENABLE_LOGGING ){
		
			System.out.println( str );
		}
	}
	
	private abstract class
	Connection
		implements GenericMessageConnectionListener, GenericMessageConnectionPropertyHandler
	{
		private final long start_time = SystemTime.getMonotonousTime();
		
		private GenericMessageConnection	gmc;
		
		private long	last_received_time	= start_time;
		private long	last_sent_time		= start_time;
				
		private long	disconnect_after	= -1;
		
		private volatile boolean	connected;
		
		private volatile boolean failed;
		
		Connection()
		{
			log( getName() + ": created" );
		}
		
		protected void
		setConnection(
			GenericMessageConnection	_gmc )
		{
			gmc = _gmc;
			
			addConnection( this );
					
			gmc.addInboundRateLimiter( inbound_limiter );
			
			gmc.addListener( this );
		}

		protected void
		setConnected()
		{
			if ( this instanceof OutboundConnection ){
			
				log( getName() + ": connected (" + ( SystemTime.getMonotonousTime() - start_time )/1000 + "s)");
			}
			
			connected	= true;
		}
		
		private void
		timerTick(
			long	now )
		{
			if ( disconnect_after >= 0 && now > disconnect_after ){
				
				failed( new Exception( "Force disconnect" ));
				
				return;
			}
			
			if ( !connected ){
				
				return;
			}
			
			if ( now - last_received_time > 120*1000 ){
				
				failed( new Exception( "Inactivity timeout" ));
				
			}else if ( now - last_sent_time >= 60*1000 ){
				
				Map map = new HashMap<>();
								
				send( MT_PROXY_KEEP_ALIVE, map );
			}
		}
		
		protected int
		getAgeSeconds(
			long		now )
		{
			return((int)((now - start_time)/1000));
		}
		
		protected void
		setDisconnectAfterSeconds(
			int		secs )
		{
			if ( secs < 0 ){
				
				disconnect_after = -1;
				
			}else{
				
				disconnect_after = SystemTime.getMonotonousTime() + secs*1000;
			}
		}
		
		protected void
		send(
			int		type,
			Map		map )
		{
			last_sent_time	= SystemTime.getMonotonousTime();
			
			map.put( "type", type );
			
			map.put( "ver", I2PHelperAltNetHandlerTor.LOCAL_VERSION );

			if ( type != MT_PROXY_KEEP_ALIVE ){
				
				log( "send " + map );
			}
			
			PooledByteBuffer buffer = null;
			
			try{
				buffer = plugin.getPluginInterface().getUtilities().allocatePooledByteBuffer( BEncoder.encode(map));
				
				gmc.send( buffer );
				
				buffer = null;
				
			}catch( Throwable e ){
								
				if ( buffer != null ){
					
					buffer.returnToPool();
				}
				
				failed( e );
			}
		}
		
		@Override
		public void 
		receive(
			GenericMessageConnection	connection, 
			PooledByteBuffer 			message )
					
			throws MessageException
		{
			last_received_time = SystemTime.getMonotonousTime();
					
			try{
				Map map = BDecoder.decode( message.toByteArray());
								
				int	type = ((Number)map.get( "type" )).intValue();
				
				if ( type == MT_PROXY_CLOSE ){
					
					disconnect_after = 0;
					
				}else if ( type != MT_PROXY_KEEP_ALIVE ){
				
					log( "receive " + map );

					receive( type, map );
				}
				
			}catch( Throwable e ){
										
				failed( e );

			}finally{
				
				message.returnToPool();
			}
		}
		
		protected abstract void
		receive(
			int		type,
			Map		map )

			throws Exception;
		
		@Override
		public void 
		failed(
			GenericMessageConnection	connection, 
			Throwable					error ) 
					
			throws MessageException
		{
			failed( error );
		}
		
		protected void
		closingDown()
		{
			if ( connected ){
			
				Map map = new HashMap<>();
				
				send( MT_PROXY_CLOSE, map );
			}
		}
		
		protected void
		close()
		{
			synchronized( this ){
				
				if ( failed ){
					
					return;
				}
				
				failed = true;
			}
			
			try{
				try{
					gmc.close();
					
				}catch( Throwable e ){
				}
								
				removeConnection( this );
				
			}finally{
				
				setClosed();
			}
		}
		
		protected void
		failed(
			Throwable 	error )
		{
			synchronized( this ){
				
				if ( failed ){
					
					return;
				}
				
				failed = true;
			}
			
			try{
				if ( disconnect_after == -1 ){
				
						// unexpected
					
					log( getName() + " failed: " + Debug.getNestedExceptionMessage(error));
				}
				
				try{
					gmc.close();
					
				}catch( Throwable e ){
				}
								
				removeConnection( this );
				
			}finally{
				
				setClosed();
			}
		}
		
		protected void
		setClosed()
		{
		}
		
		protected abstract String
		getName();
		
		protected String
		getString()
		{
			long now = SystemTime.getMonotonousTime();
			
			return( getName() + ", idle_in=" + (now - last_received_time)/1000 + "s, idle_out=" + (now - last_sent_time)/1000 + "s");
		}
		
		protected boolean
		isClosed()
		{
			return( failed );
		}
		
		@Override
		public Object 
		getConnectionProperty(
			String property_name )
		{
			return( null );
		}
	}
	
	private abstract class
	OutboundConnection
		extends Connection
	{	
		private final InetSocketAddress		target;
		
		private
		OutboundConnection(
			InetSocketAddress		_target )
		
			throws Exception
		{
			target	= _target;
			
			GenericMessageEndpoint ep = msg_registration.createEndpoint( target );
			
			ep.addTCP( target );
			
			GenericMessageConnection gmc = msg_registration.createConnection( ep );
							
			setConnection( gmc );
			
			try{
				gmc.connect( this );
				
			}catch( Throwable e ){
				
				failed( e );
			}
		}
		
		protected InetSocketAddress
		getAddress()
		{
			return( target );
		}
		
		protected String
		getHost()
		{
			return( AddressUtils.getHostAddress(target));
		}
		
		protected String
		getString()
		{
			return( super.getString() + ": " + getHost());
		}
	}
	
	private class
	OutboundConnectionProxy
		extends OutboundConnection
	{
		public static final int STATE_INITIALISING	= 0;
		public static final int STATE_ACTIVE		= 1;
		public static final int STATE_FAILED		= 2;
		
		private final String	tep_pure_host;
		private final int		tep_pure_port;
		private final byte[]	tep_pure_pk;
		
		private final String	uid;
		
		private volatile int state	= STATE_INITIALISING;
		
		private volatile boolean	has_been_active;
		
		private Map<Long,ActiveRequest>		active_requests = new IdentityHashMap<>();
		
		private
		OutboundConnectionProxy(
			InetSocketAddress		target )
		
			throws Exception
		{
			super( target );
				
			tep_pure_host	= tep_pure.getHost();
			tep_pure_port	= tep_pure.getPort();
			
			tep_pure_pk		= I2PHelperPlugin.TorEndpoint.getPublicKeyBytes( tep_pure_host );
			
			byte[] _uid = new byte[32];
			
			RandomUtils.nextSecureBytes(_uid);
						
			uid = Base32.encode(_uid);
		}
		
		@Override
		protected String
		getName()
		{
			return( "Proxy out" );
		}
		
		protected String
		getUID()
		{
			return( uid );
		}
		
		protected void
		setState(
			int		_state )
		{
			synchronized( this ){
				
				if ( state == STATE_FAILED ){
					
					return;
				}
				
				state = _state;
				
				if ( state == STATE_ACTIVE ){
					
					has_been_active = true;
				}
			}
		}
		
		protected int
		getState()
		{
			return( state );
		}
		
		protected boolean
		hasBeenActive()
		{
			return( has_been_active );
		}
		
		protected void
		addRequest(
			ProxyRequest			request,
			ProxyRequestListener	listener )
		{
			ActiveRequest	ar;
			
			synchronized( this ){
				
				if ( state == STATE_ACTIVE ){
					
					ar = new ActiveRequest( request, listener );
					
					active_requests.put( request.getSequence(), ar );
					
				}else{
					
					ar = null;
				}
			}
			
			if ( ar == null ){
				
				listener.failed( request );
				
			}else{
										
				try{
					byte[]	key		= request.getKey();

					MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
					
					sha256.update( "TorProxyDHT::key".getBytes( Constants.UTF_8 ));
					
					sha256.update( key );
					
					byte[] derived_key = sha256.digest();
					
					Map proxy_request = new HashMap<>();

					proxy_request.put( "op_key", derived_key );
					proxy_request.put( "op_seq", request.getSequence());

					if ( request.getType() == ProxyRequest.RT_PUT ){
						
						Map value = ((ProxyRequestPut)request).getValue();
						
						value = new HashMap<>( value );	// copy as we add to it
						
						value.put( "h", tep_pure_pk );
						value.put( "p", tep_pure_port );

						byte[] bytes = BEncoder.encode(value);
						
						byte[] sig = tep_pure.sign( bytes );
												
						value.put( "z", sig );
				
						byte[] masked_value = BEncoder.encode( value );
						
						maskValue( key, masked_value );
												
						proxy_request.put( "op_type", PROXY_OP_PUT );
						proxy_request.put( "op_value", masked_value );
											
					}else if ( request.getType() == ProxyRequest.RT_GET ){
						
						proxy_request.put( "op_type", PROXY_OP_GET );

					}else{
						
						throw( new Exception( "eh?" ));
					}
					
					send( MT_PROXY_OP_REQUEST, proxy_request );

				}catch( Throwable e ){
					
					Debug.out( e );
					
					synchronized( this ){
						
						active_requests.remove( request.getSequence());
					}
					
					listener.failed( request );
				}
			}
		}
		
		@Override
		public void 
		connected(
			GenericMessageConnection connection )
		{
			setConnected();
			
			try{
				Map payload = new TreeMap<>();
				
				payload.put( "source_host", tep_pure_host );
				payload.put( "source_port", tep_pure_port );
				payload.put( "target", getHost());
				payload.put( "uid", uid );
				
				byte[] bytes = BEncoder.encode(payload);
				
				byte[] sig = tep_pure.sign( bytes );
				
				Map map = new HashMap<>();
					
				map.put( "payload", payload );
				map.put( "sig", sig );
				
				send( MT_PROXY_ALLOC_REQUEST, map );
				
			}catch( Throwable  e ){
				
				failed( e );
			}
		}
				
		@Override
		public void 
		receive(
			int		type,
			Map		map )
		
			throws Exception
		{
			if ( type == MT_PROXY_ALLOC_OK_REPLY ){
				
				if ( getState() == STATE_INITIALISING ){
					
					map = BDecoder.decodeStrings( map );
					
					String iid = (String)map.get( "iid" );
					
					setState( STATE_ACTIVE );
					
					log( "Proxy client setup complete: iid=" + iid );
					
					proxyClientSetupComplete( this, iid );
				}
			}else if ( type == MT_PROXY_ALLOC_FAIL_REPLY ){
				
				map = BDecoder.decodeStrings( map );
				
				List<Map> contacts = (List<Map>)map.get( "contacts" );
				
				List<InetSocketAddress> isas = new ArrayList<>();
				
				for ( Map m: contacts ){
					
					String	host = (String)m.get( "host" );
					int		port = ((Number)m.get( "port" )).intValue();
					
					InetSocketAddress isa = InetSocketAddress.createUnresolved(host, port);
					
					isas.add( isa );
				}
				
				addBackupContacts( isas );
				
				log( "Proxy client setup failed: " + map );
				
				close();
				
			}else if ( type == MT_PROXY_OP_REPLY ){
				
				long seq = ((Number)map.get( "op_seq" )).longValue();
				
				ActiveRequest	ar;
				
				synchronized( this ){
					
					ar = active_requests.remove( seq );
				}
				
				if ( ar == null ){
					
					throw( new Exception( "Reply to unknown request received" ));
				}
				
				try{
					ProxyRequest request = ar.getRequest();
					
					int request_type = request.getType();
					
					if ( request_type == ProxyRequest.RT_GET ){
						
						List<byte[]> l_values = (List<byte[]>)map.get( "op_values" );
						
						List<Map> values = new ArrayList<>();
						
						for ( byte[] masked_value: l_values ){
							
							maskValue( request.getKey(), masked_value );
							
							Map value = BDecoder.decode( masked_value );
							
							byte[] sig = (byte[])value.remove( "z" );
							
							byte[]		host_pk	= (byte[])value.get( "h" );
							
							PublicKey source_pk = I2PHelperPlugin.TorEndpoint.getPublicKey( host_pk );
							
							byte[] value_bytes = BEncoder.encode( value );
	
							if ( I2PHelperPlugin.TorEndpoint.verify( source_pk, value_bytes, sig )){
								
									// don't remove "h" as some uses rely on it
								
								values.add( value );
								
							}else{
								
								log( "value verification failed, ignoring" );
							}
						}
						
						((ProxyRequestGet)request).setValues( values );
					}
					
					ar.getListener().complete( request );
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}else{
				
				throw( new Exception( "Unknown message type: " + type ));
			}
		}
		
		@Override
		protected void 
		setClosed()
		{
			setState( STATE_FAILED );
			
			List<ActiveRequest>	failed_requests;
			
			synchronized( this ){
				
				failed_requests = new ArrayList<>( active_requests.values());
				
				active_requests.clear();
			}
			
			for ( ActiveRequest ar: failed_requests ){
				
				try{
					ar.getListener().failed( ar.getRequest());
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
		}
		
		@Override
		protected String
		getString()
		{
			return( super.getString() + "; state=" + getState());
		}
		
		private class
		ActiveRequest
		{
			final ProxyRequest			request;
			final ProxyRequestListener	listener;
			
			ActiveRequest(
				ProxyRequest			_request,
				ProxyRequestListener	_listener )
			{
				request 	= _request;
				listener	= _listener;
			}
			
			protected ProxyRequest
			getRequest()
			{
				return( request );
			}
			
			protected ProxyRequestListener
			getListener()
			{
				return( listener );
			}
		}
	}
	
	private class
	OutboundConnectionProxyProbe
		extends OutboundConnection
	{
		final InboundConnectionProxy	for_connection;
		final String					uid;
		
		protected volatile boolean	success;
		
		private
		OutboundConnectionProxyProbe(
			InboundConnectionProxy	_for_connection,
			InetSocketAddress		target,
			String					_uid )
		
			throws Exception
		{
			super( target );
			
			for_connection	= _for_connection;
			uid				= _uid;
			
			setDisconnectAfterSeconds( 60 );
		}
		
		@Override
		protected String
		getName()
		{
			return( "Probe out" );
		}
		
		@Override
		public void 
		connected(
			GenericMessageConnection connection )
		{
			setConnected();
			
			try{
					// we use the probe to at least show that the originator is a real service
				
				Map map = new HashMap<>();
				
				map.put( "uid", uid );
				map.put( "source_host", tep_mix.getHost());
				
				send( MT_PROXY_PROBE_REQUEST, map );
				
			}catch( Throwable  e ){
				
				failed( e );
			}
		}
				
		@Override
		public void 
		receive(
			int		type,
			Map		map )
		{
			if ( type == MT_PROXY_PROBE_REPLY ){
				
				success = true;
				
				for_connection.setProbeReplyReceived();
			}
				
			close();
		}
		
		@Override
		protected void 
		setClosed()
		{
			if ( !success ){
				
				for_connection.setProbeFailed();
			}
		}
	}
	
	private abstract class
	InboundConnection
		extends Connection
	{
		private
		InboundConnection(
			GenericMessageConnection		gmc )
		{
			setConnection( gmc );
			
			setConnected();
		}
				
		@Override
		public void 
		connected(
			GenericMessageConnection connection )
		{
			// nothing here
		}
	}
	
	private class
	InboundConnectionProxy
		extends InboundConnection
	{
		public static final int STATE_INITIALISING	= 0;
		public static final int STATE_PROBE_SENT	= 1;
		public static final int STATE_PROBE_FAILED	= 2;
		public static final int STATE_ACTIVE		= 3;
		public static final int STATE_FAILED		= 4;
				
		private volatile int state	= STATE_INITIALISING;

		private String		source_host;
		private PublicKey 	source_pk;
		
		private
		InboundConnectionProxy(
			GenericMessageConnection		gmc )
		{
			super( gmc );
		}
			
		@Override
		protected String
		getName()
		{
			return( "Proxy in" );
		}
		
		protected void
		setState(
			int		_state )
		{
			synchronized( this ){
				
				if ( state == STATE_FAILED ){
					
					return;
				}
				
				state = _state;
			}
		}
		
		protected int
		getState()
		{
			return( state );
		}
		
		@Override
		protected void 
		receive(
			int 	type,
			Map 	request ) 
			
			throws Exception
		{
			if ( type == MT_PROXY_ALLOC_REQUEST ){
				
				if ( !ENABLE_PROXY_SERVER ){
					
					throw( new Exception( "Proxy server disabled" ));
				}
				
				Map payload_raw = (Map)request.get( "payload" );
												
				Map payload = BDecoder.decodeStrings( payload_raw );
				
				String target	= (String)payload.get( "target" );
				
				if ( target != null ){
					
					if ( !target.equals( tep_mix.getHost())){
					
						throw( new Exception( "target host mismatch" ));
					}
				}
				
				source_host	= (String)payload.get( "source_host" );
				
				boolean too_many = false;
				
				synchronized( connections ){
					
					if ( server_proxies.size() >= MAX_SERVER_PROXIES ){
	
						too_many = true;
						
					}else{
					
						for ( InboundConnectionProxy sp: server_proxies ){
							
							if ( source_host.equals( sp.source_host )){
								
								throw( new Exception( "Duplicate proxy" ));
							}
						}
						
						server_proxies.add( this );
					}
				}
				
				if ( too_many ){
					
					Map reply = new HashMap<>();
										
					List<Map> l_contacts = new ArrayList<>();
					
					reply.put( "contacts", l_contacts );

					DHTTransportAlternativeNetwork net = DHTUDPUtils.getAlternativeNetwork( DHTTransportAlternativeNetwork.AT_TOR );
					
					if ( net != null ){
					
						List<DHTTransportAlternativeContact> contacts = DHTUDPUtils.getAlternativeContacts( DHTTransportAlternativeNetwork.AT_TOR, 16 );

						Collections.shuffle( contacts );
							
						for ( int i=0;i<5;i++){
							
							DHTTransportAlternativeContact contact = contacts.get( i );
							
							Map m = new HashMap<>();
							
							InetSocketAddress isa = net.getNotionalAddress( contact );
							
							m.put( "host", AddressUtils.getHostAddress( isa ));
							m.put( "port", isa.getPort());
							
							l_contacts.add( m );
						}
					}
					
					send( MT_PROXY_ALLOC_FAIL_REPLY, reply );

						// if we fail immediately the reply doesn't get sent...
					
					setDisconnectAfterSeconds( 10 );
					
					return;
				}
				
				int		source_port	= ((Number)payload.get( "source_port" )).intValue();
				String	uid			= (String)payload.get( "uid" );
				
				source_pk = I2PHelperPlugin.TorEndpoint.getPublicKey( source_host );
				
				byte[] sig = (byte[])request.get( "sig" );

				byte[] payload_bytes = BEncoder.encode( payload_raw );

				if ( !I2PHelperPlugin.TorEndpoint.verify( source_pk, payload_bytes, sig )){
					
					throw( new Exception( "Signature verification failed" ));
				}
				
				InetSocketAddress source_isa = InetSocketAddress.createUnresolved( source_host, source_port);
				
				new OutboundConnectionProxyProbe( this, source_isa, uid );
				
				setState( STATE_PROBE_SENT );
				
			}else if ( type == MT_PROXY_OP_REQUEST ){
				
				int		op_type = ((Number)request.get( "op_type" )).intValue();
				long	seq		= ((Number)request.get( "op_seq" )).longValue();
				
				byte[] key = (byte[])request.get( "op_key" );
				
				Map reply = new HashMap<>();

				switch( op_type ){
				
					case PROXY_OP_PUT:{
	
						byte[] value = (byte[])request.get( "op_value" );
						
						proxyDHTPut( key, value );
												
						reply.put( "op_seq", seq );
												
						break;
					}
					case PROXY_OP_GET:{
							
						List<byte[]> values = proxyDHTGet( key );
												
						reply.put( "op_seq", seq );
						reply.put( "op_values", values );
												
						break;
					}
					default:{
						
						throw( new Exception( "Invalid op type: " + op_type ));
					}
				}
						
				send( MT_PROXY_OP_REPLY, reply );

			}else{
					
				throw( new Exception( "Invalid message type" ));
			}
		}
		
		protected void
		setProbeReplyReceived()
		{
			log( "Proxy server setup complete: iid=" + instance_id );
		
			setState( STATE_ACTIVE );

			Map reply = new HashMap<>();
				
			reply.put( "iid", instance_id );
			
			send( MT_PROXY_ALLOC_OK_REPLY, reply );
			
			proxyServerSetupComplete( this,instance_id );
		}
		
		protected void
		setProbeFailed()
		{
			log( "Probe failed" );
			
			setState( STATE_PROBE_FAILED );
			
			Map reply = new HashMap<>();
						
			List<Map> l_contacts = new ArrayList<>();
			
			reply.put( "contacts", l_contacts );
			
			send( MT_PROXY_ALLOC_FAIL_REPLY, reply );

				// if we fail immediately the reply doesn't get sent...
			
			setDisconnectAfterSeconds( 10 );
		}
		
		@Override
		protected void 
		setClosed()
		{
			setState( STATE_FAILED );
		}
		
		protected String
		getString()
		{
			return( super.getString() + ": " + source_host + "; state=" + getState());
		}
	}
	
	private class
	InboundConnectionProbe
		extends InboundConnection
	{
		private String		source_host;
		
		private
		InboundConnectionProbe(
			GenericMessageConnection		gmc )
		{
			super( gmc );
		}
			
		@Override
		protected String
		getName()
		{
			return( "Probe in" );
		}
		
		@Override
		protected void 
		receive(
			int 	type,
			Map 	request ) 
			
			throws Exception
		{
			if ( type == MT_PROXY_PROBE_REQUEST ){
								
				setDisconnectAfterSeconds( 60 );

				request = BDecoder.decodeStrings( request );
				
				String uid = (String)request.get( "uid" );
				
				source_host = (String)request.get( "source_host" );
				
				OutboundConnectionProxy cp = current_client_proxy;
				
				if ( cp != null && cp.getUID().equals( uid )){
				
					Map reply = new HashMap<>();
				
					send( MT_PROXY_PROBE_REPLY, reply );
					
				}else{
					
					throw( new Exception( "Probe request UID mismatch" ));
				}
			}else{
				
				throw( new Exception( "Invalid message type" ));
			}
		}
	}
	
	private interface
	ProxyRequestListener
	{
		public void
		complete(
			ProxyRequest	request );
		
		public void
		failed(
			ProxyRequest	request );
	}
	
	private abstract class
	ProxyRequest
	{
		public static final int RT_PUT			= 1;
		public static final int RT_GET			= 2;
		public static final int RT_REMOVE		= 3;
	
		private final long			seq	= proxy_request_seq.incrementAndGet();
		private final byte[]		key;
		
		protected
		ProxyRequest(
			byte[]		_key )
		{
			key		= _key;
		}
		
		protected long
		getSequence()
		{
			return( seq );
		}
		
		protected abstract int
		getType();
		
		protected byte[]
		getKey()
		{
			return( key );
		}
	}
	
	private class
	ProxyRequestPut
		extends ProxyRequest
	{
		private final Map	value;
		
		protected
		ProxyRequestPut(
			byte[]		_key,
			Map			_value )
		{
			super( _key );
			
			value = _value;
		}
		
		protected int
		getType()
		{
			return( RT_PUT );
		}
		
		protected Map
		getValue()
		{
			return( value );
		}
	}
	
	private class
	ProxyRequestGet
		extends ProxyRequest
	{
		private final List<Map>		values = new ArrayList<>();
		
		protected
		ProxyRequestGet(
			byte[]		_key )
		{
			super( _key );
		}
		
		protected int
		getType()
		{
			return( RT_GET );
		}
		
		protected void
		setValues(
			List<Map> v )
		{
			values.addAll(v);
		}
		
		protected List<Map>
		getValues()
		{
			return( values );
		}
	}
}
