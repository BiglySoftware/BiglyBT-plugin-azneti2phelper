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

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.parg.azureus.plugins.networks.i2p.I2PHelperAltNetHandlerTor;
import org.parg.azureus.plugins.networks.i2p.I2PHelperPlugin;

import com.biglybt.core.dht.control.DHTControl;
import com.biglybt.core.dht.transport.DHTTransportAlternativeContact;
import com.biglybt.core.dht.transport.DHTTransportAlternativeNetwork;
import com.biglybt.core.dht.transport.udp.impl.DHTUDPUtils;
import com.biglybt.core.networkmanager.NetworkManager;
import com.biglybt.core.util.AENetworkClassifier;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.AddressUtils;
import com.biglybt.core.util.BDecoder;
import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.ByteArrayHashMap;
import com.biglybt.core.util.ByteFormatter;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.CopyOnWriteList;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SHA1Hasher;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimeFormatter;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.pif.PluginAdapter;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.ddb.DistributedDatabase;
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
import com.biglybt.plugin.dht.DHTPluginContact;
import com.biglybt.plugin.dht.DHTPluginInterface;
import com.biglybt.plugin.dht.DHTPluginOperationAdapter;
import com.biglybt.plugin.dht.DHTPluginOperationListener;
import com.biglybt.plugin.dht.DHTPluginValue;

import net.i2p.data.Base32;


public class 
TorProxyDHT
{
	private static final boolean	LOOPBACK			= false;
	private static final boolean	ENABLE_LOGGING		= true;
	
	private static final boolean	ENABLE_PROXY_CLIENT = true;
	private static final boolean	ENABLE_PROXY_SERVER = true;
		
	private static final int		MIN_SERVER_VERSION	= 2;
	
	private static final int TIMER_PERIOD = 10*1000;
	
	private static final int REPUBLISH_CHECK_PERIOD		= 5*60*1000;
	private static final int REPUBLISH_CHECK_TICKS		= REPUBLISH_CHECK_PERIOD / TIMER_PERIOD;

	private static final int PUT_TIME	= 5*60*1000;		// estimate!
			
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
	private static final int PROXY_OP_REMOVE	= 3;
	
	private static final int MAX_SERVER_PROXIES	= 5;
	
	private static final int MAX_PROXY_KEY_STATE			= 200;
	private static final int MAX_GLOBAL_KEY_STATE			= MAX_PROXY_KEY_STATE * MAX_SERVER_PROXIES;

		// methods introduced in 3301 - remove when safe to do so!
	
	private final Method method_DHTPluginInterface_put;
	private final Method method_DHTPluginInterface_remove;
	
	private final I2PHelperPlugin		plugin;
	private final PluginInterface		plugin_interface;
	
	private volatile boolean closing_down;
	
	private DistributedDatabase	ddb ;

	private final String	instance_id;
	private final int		time_skew = RandomUtils.nextInt(60)-30;
	
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
	
	private int	proxy_client_get_fails;
	
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

	private AtomicLong						proxy_request_seq	= new AtomicLong( RandomUtils.nextInt( 4096 ));
	private LinkedList<ProxyLocalRequest>	proxy_requests		= new LinkedList<>();
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
		
		plugin_interface = plugin.getPluginInterface();
		
		Method method1 = null;
		Method method2 = null;
		
		try{		
			method1 = DHTPluginInterface.class.getMethod( 
						"put", 
						byte[].class, String.class, byte[].class, short.class, boolean.class, DHTPluginOperationListener.class );
			
			method2 = DHTPluginInterface.class.getMethod( 
					"remove", 
					byte[].class, String.class, short.class, DHTPluginOperationListener.class );

			
		}catch( Throwable e ){
		}
		
		method_DHTPluginInterface_put		= method1;
		method_DHTPluginInterface_remove	= method2;
		
		byte[] temp = new byte[8];
		
		RandomUtils.nextSecureBytes( temp );
		
		instance_id = Base32.encode( temp );
		
		ConnectionManager cman = plugin_interface.getConnectionManager();

		int inbound_limit = 50*1024;

		inbound_limiter 	= cman.createRateLimiter( "TorDHTProxy:in", inbound_limit );
				
		plugin_interface.addListener(
			new PluginAdapter()
			{
				@Override
				public void 
				closedownInitiated()
				{
					closing_down = true;
					
					for ( Connection con: connections ){
						
						con.closingDown();
					}
				}
			});
	}
	
	public void
	initialise()
	{		
		ddb = plugin_interface. getDistributedDatabase();

		tep_mix		= plugin.getTorEndpoint( 0 );
		tep_pure	= plugin.getTorEndpoint( 1 );
		
		try{
			int mix_port = tep_mix.getPort();
			
			msg_registration =
					plugin_interface.getMessageManager().registerGenericMessageType(
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
					TIMER_PERIOD,
					new TimerEventPerformer()
					{
						int tick_count = -1;
						
						@Override
						public void 
						perform(
							TimerEvent event)
						{
							tick_count++;
							
							checkRequestTimeouts();
							
							long now_mono = SystemTime.getMonotonousTime();
							
							if ( proxy_client_consec_fail_count < 10 || tick_count % 3 == 0 ){
							
								checkClientProxy( true );
							}
							
							if ( tick_count % 3 == 0 ){
								
								if ( ENABLE_LOGGING ){
									
									printGlobalKeyState();
									
									printLocalKeyState();
								
									if ( tick_count % 6 == 0 ){
										
										OutboundConnectionProxy ccp = current_client_proxy;
																				
										log( "Connections=" + connections.size());
										
										if ( ccp != null ){
											
											log( "    " + ccp.getString());
										}
										
										for ( InboundConnection sp: server_proxies ){
											
											log( "    " + sp.getString());
										}
									}
								}

								checkServerProxies();
								
								for ( Connection con: connections ){
										
									con.timerTick( now_mono );
									
									if ( 	con != current_client_proxy && 
											!( con instanceof InboundConnectionProxy && server_proxies.contains((InboundConnectionProxy)con ))){
										
										if ( con.getAgeSeconds() > 120 ){
											
											con.failed( new Exception( "Dead connection" ));
										}
									}
								}
							}
							
							if ( tick_count % REPUBLISH_CHECK_TICKS == 0 ){
								
								checkRepublish();
								
								checkGlobalKeyState();
							}
						}
					});
				
			checkClientProxy( true );
			
			try{
				byte[]	random_key = new byte[16];
				
				RandomUtils.nextSecureBytes( random_key );
				
				byte[]	random_value = new byte[4+RandomUtils.nextInt(12)];
				
				RandomUtils.nextSecureBytes( random_value );
				
				proxyPut( random_key, random_value, new HashMap<String,Object>());
				
			}catch( Throwable e ){
				
				Debug.out( e );
			}
		}catch( Throwable e ){
			
			Debug.out( e );
		}
	}
	
	public void
	proxyTrackerAnnounce(
		byte[]					torrent_hash,
		boolean					is_seed,
		TorProxyDHTListener		listener )
	{
		log( "proxyTrackerAnnounce: " + ByteFormatter.encodeString(torrent_hash) + "/" + is_seed );

		try{
			MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
						
			sha256.update( "TorProxyDHT::torrent_hash".getBytes( Constants.UTF_8 ));
			
			sha256.update( torrent_hash );
			
			byte[] key = sha256.digest();
				
			Map<String,Object> payload = new HashMap<>();
			
			payload.put( "s", is_seed?1:0 );
			
			if ( NetworkManager.REQUIRE_CRYPTO_HANDSHAKE ){
				
				payload.put( "c", 1 );
			}
			
			Map<String,Object>	options = new HashMap<>();
			
			options.put( "f", is_seed?DHTPluginInterface.FLAG_SEEDING:DHTPluginInterface.FLAG_DOWNLOADING );
			
			proxyLocalPut( 
				key, payload, options,
				new ProxyRequestAdapter()
				{
					@Override
					public void 
					complete(
						byte[] key, boolean timeout)
					{
						log( "   proxyTrackerAnnounce complete" );

						if ( listener != null ){
							
							listener.proxyComplete( torrent_hash, timeout );
						}
					}
				});
			
		}catch( Throwable e ){
			
			Debug.out( e );
			
			if ( listener != null ){
				
				listener.proxyComplete( torrent_hash, true );
			}
		}
	}
	
	public void
	proxyTrackerGet(
		byte[]					torrent_hash,
		boolean					is_seed,
		int						num_want,
		TorProxyDHTListener		listener )
	{
		log( "proxyTrackerGet: " + ByteFormatter.encodeString(torrent_hash) + "/" + is_seed + "/" + num_want );

		try{
			MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
			
			sha256.update( "TorProxyDHT::torrent_hash".getBytes( Constants.UTF_8 ));
			
			sha256.update( torrent_hash );
			
			byte[] key = sha256.digest();
	
			long timeout = 2*60*1000;
			
			Map<String,Object>	options = new HashMap<>();
			
			options.put( "f", is_seed?DHTPluginInterface.FLAG_SEEDING:DHTPluginInterface.FLAG_DOWNLOADING );
			options.put( "t", timeout );
			options.put( "n", num_want );
	
			proxyLocalGet( 
				key, 
				options, 
				timeout,
				new ProxyRequestListener()
				{	
					@Override
					public void 
					valuesRead(
						byte[]						key,
						List<Map<String,Object>> 	values )
					{
						for ( Map<String,Object> v: values ){
							
							try{
								String	host = (String)v.get( "h" );
								int		port = ((Number)v.get( "p" )).intValue();
							
								boolean is_seed = ((Number)v.get( "s" )).intValue() != 0;
								
								Number c = (Number)v.get( "c" );
								
								boolean require_crypto = false;
								
								if ( c != null ){
									
									require_crypto = c.intValue() == 1;
								}
								
								log( "   proxyTrackerGet -> " + host + ":" + port + "/" + is_seed + "/" + require_crypto );

								listener.proxyValueRead(
									InetSocketAddress.createUnresolved(host, port),
									is_seed,
									require_crypto );
								
							}catch( Throwable e ){
								
								Debug.out( e );
							}
						}
					}
					
					@Override
					public void 
					complete(
						byte[]			 	key, 
						boolean 			timeout )
					{
						log( "   proxyTrackerGet complete" );
						
						try{
							listener.proxyComplete( torrent_hash, timeout );
							
						}catch( Throwable e ){
							
							Debug.out( e );
						}
					}
				});
			
		}catch( Throwable e ){
			
			Debug.out( e );
			
			listener.proxyComplete( torrent_hash, true );
		}
	}
	
	public void
	proxyTrackerRemove(
		byte[]				torrent_hash,
		TorProxyDHTListener	listener )
	{
		log( "proxyTrackerRemove: " + ByteFormatter.encodeString(torrent_hash));
		
		try{
			MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
			
			sha256.update( "TorProxyDHT::torrent_hash".getBytes( Constants.UTF_8 ));
			
			sha256.update( torrent_hash );
			
			byte[] key = sha256.digest();
			
			proxyLocalRemove( 
				key,
				new ProxyRequestAdapter()
				{
					@Override
					public void 
					complete(
						byte[] key, boolean timeout)
					{
						log( "   proxyTrackerRemove complete" );
						
						if ( listener != null ){
							
							listener.proxyComplete( torrent_hash, timeout );
						}
					}
				});
			
		}catch( Throwable e ){
			
			Debug.out( e );
			
			if ( listener != null ){
			
				listener.proxyComplete( torrent_hash, true );
			}
		}
	}
	
	public void
	proxyPut(
		byte[]				key,
		byte[]				value,
		Map<String,Object>	options )
	
		throws Exception
	{
		Map<String,Object> map = new HashMap<>();
		
		map.put( "v", value );
		
		proxyLocalPut(
			key, map, options,
			new ProxyRequestAdapter(){
				
				@Override
				public void 
				complete(
					byte[] 	key, 
					boolean timeout )
				{
					log( "proxyPut complete" );
				}
			});
	}

	public void
	proxyGet(
		byte[]			key )
	{
		proxyLocalGet(
			key, null, 2*60*1000,
			new ProxyRequestListener(){
				
				@Override
				public void 
				valuesRead(
					byte[]						key, 
					List<Map<String,Object>>	values)
				{
					log( "proxyGet valuesRead: values=" + values.size());
					
					for ( Map<String,Object> m: values ){
						
						log( "    " + BDecoder.decodeStrings(m));
					}
				}
				
				@Override
				public void 
				complete(
					byte[] key, 
					boolean timeout)
				{
					log( "proxyGet complete, timeout=" + timeout );
				}
			});
	}
	
	public void
	proxyRemove(
		byte[]			key )
	
		throws Exception
	{		
		proxyLocalRemove(
			key,
			new ProxyRequestAdapter(){
				
				@Override
				public void 
				complete(
					byte[] 	key, 
					boolean timeout )
				{
					log( "proxyRemove complete" );
				}
			});
	}
	
		// local operations
	
	
	private void
	proxyLocalPut(
		byte[]					key,
		Map<String,Object>		value,
		Map<String,Object>		options,
		ProxyRequestListener	listener )
	
		throws Exception
	{
			// "h", "p", "t" and "z" are reserved
		
		if ( 	value.containsKey( "h" ) || 
				value.containsKey( "p" ) ||
				value.containsKey( "t" ) ||
				value.containsKey( "z" )){
			
			throw( new Exception( "Invalid map, uses reserved keys" ));
		}
		
		ProxyLocalRequestPut request = new ProxyLocalRequestPut( key, value, listener );
		
		request.setOptions( options );
				
		addRequest( request );
	}
	
	private void
	proxyLocalGet(
		byte[]					key,
		Map<String,Object>		options,
		long					timeout,
		ProxyRequestListener	listener )
	{
		ProxyLocalRequestGet	request = new ProxyLocalRequestGet( key, listener );
		
		request.setOptions( options );
				
		request.setTimeout( timeout );
		
		addRequest( request );		
	}
	
	private void
	proxyLocalRemove(
		byte[]					key,
		ProxyRequestListener	listener )
	{
		addRequest( new ProxyLocalRequestRemove( key, listener ));		
	}
	
	class
	LocalKeyState
	{
		private ProxyLocalRequest	pending_request;
		private ProxyLocalRequest	active_request;
		
		private long				last_ok_time;
		private ProxyLocalRequest	last_ok_request;
	}
	
	private ByteArrayHashMap<LocalKeyState>	local_key_state = new ByteArrayHashMap<>();
	
	private void
	printLocalKeyState()
	{
		synchronized( proxy_requests ){
			
			log( "LKS size=" + local_key_state.size());
			
			long now = SystemTime.getCurrentTime();
			
			for ( byte[] key: local_key_state.keys()){
				
				LocalKeyState lks = local_key_state.get( key );
					
				String age_str = "";

				if ( lks.last_ok_time > 0 ){
										
					long elapsed = now - lks.last_ok_time;

					age_str = ", age=" + TimeFormatter.formatColon(elapsed/1000);
				}
				
				String str = 
					"pend=" + (lks.pending_request==null?"":lks.pending_request.getString()) + 
					", act=" + (lks.active_request==null?"":lks.active_request.getString()) + 
					", last=" +(lks.last_ok_request==null?"":lks.last_ok_request.getString()) +
					age_str;
						
				log( "    " + ByteFormatter.encodeString(key,0,Math.min(key.length,8)) + " -> " + str );
			}
		}
	}
	
	private void
	addRequest(
		ProxyLocalRequest		request )
	{
		synchronized( proxy_requests ){
			
			boolean queue_request = false;
			
			int request_type = request.getType();
			
			if ( request_type == ProxyLocalRequest.RT_GET ){
				
					// get requests all get queued and will timeout as necessary
				
				queue_request = true;
				
			}else{
				
					// non-get requests get their local state tracked, there will only ever
					// be at most one request queued
				
				byte[] key = request.getKey();
				
				LocalKeyState	lks = local_key_state.get( key );
				
				if ( lks != null ){
					
					if ( lks.pending_request != null ){
					
						proxy_requests.remove( lks.pending_request );
					}
					
				}else{
					
					if ( local_key_state.size() >= MAX_PROXY_KEY_STATE && request_type == ProxyLocalRequest.RT_PUT ){
						
						Debug.out( "Key state size exceeded" );
						
						return;
					}
					
					lks = new LocalKeyState();
										
					local_key_state.put( key, lks );
				}
				
				lks.pending_request = request;
				
				if ( lks.active_request == null){
					
					queue_request = true;
				}
			}

			if ( queue_request ){
				
				proxy_requests.add( request );
				
				proxy_requests_sem.release();
			
				checkRequestDispatcher();
			}
		}
	}
	
	private void
	requestComplete(
		OutboundConnectionProxy		proxy,
		ProxyLocalRequest			request )
	{
		log( "request complete:" + request.getSequence() + ", elapsed=" + (SystemTime.getMonotonousTime() - request.getStartTimeMono()));
		
		request.setComplete();

		if ( request.getType() == ProxyLocalRequest.RT_GET ){
			
			synchronized( proxy_client_fail_map ){
				
				proxy_client_get_fails = 0;
			}
		}else{
			
			synchronized( proxy_requests ){
				
				byte[] key = request.getKey();
				
				LocalKeyState	lks = local_key_state.get( key );
				
				if ( lks == null ){
					
					Debug.out( "lks shouldn't be null" );
					
				}else{
					
					if ( lks.active_request == null ){
						
						Debug.out( "lks should have active request" );
					}
					
					lks.active_request = null;
					
					lks.last_ok_time		= SystemTime.getCurrentTime();
					lks.last_ok_request 	= request;
					
					if ( lks.pending_request != null ){
						
						proxy_requests.add( lks.pending_request );
						
						proxy_requests_sem.release();
					
						checkRequestDispatcher();
						
					}else{
						
						if ( request.getType() == ProxyLocalRequest.RT_REMOVE ){
							
							local_key_state.remove( key );
						}
					}
				}
			}
		}
	}
	
	private void
	requestFailed(
		OutboundConnectionProxy		proxy,
		ProxyLocalRequest			request )
	{
		log( "request failed:" + request.getSequence());
		
		request.setFailed();
		
		if ( request.getType() == ProxyLocalRequest.RT_GET ){

			OutboundConnectionProxy failed = null;
			
			synchronized( proxy_client_fail_map ){

				proxy_client_get_fails++;
				
				if ( proxy_client_get_fails >= 3 ){
					
					if ( proxy.getAgeSeconds() > 3*60 ){
						
						if ( proxy == active_client_proxy ){
							
							failed = proxy;
							
							proxy_client_fail_map.put( proxy.getHost(), "" );
							
							proxy_client_get_fails = 0;
						}
					}
				}
			}
			
			if ( failed != null ){
				
				failed.failed( new Exception( "Too many consecutive get fails" ), true );
			}
		}else{
			
			synchronized( proxy_requests ){
				
				byte[] key = request.getKey();
				
				LocalKeyState	lks = local_key_state.get( key );
				
				if ( lks == null ){
					
					Debug.out( "lks shouldn't be null" );
					
				}else{
					
					if ( lks.active_request == null ){
						
						Debug.out( "lks should have active request" );
					}
					
					lks.active_request = null;
										
					if ( lks.pending_request == null ){
						
						lks.pending_request = request;
					}
											
					proxy_requests.add( lks.pending_request );
						
					proxy_requests_sem.release();
					
					checkRequestDispatcher();
				}
			}
		}
	}
	
	private void
	checkRepublish()
	{
		synchronized( proxy_requests ){
	
			if ( active_client_proxy == null ){
				
				return;
			}
			
			String iid = active_client_proxy.getRemoteInstanceID();
			
			if ( iid == null ){
				
				return;
			}
				
			long now = SystemTime.getCurrentTime();
			
			boolean added = false;
			
			for ( byte[] key: local_key_state.keys()){
				
				LocalKeyState lks = local_key_state.get( key );
				
				if ( lks.pending_request == null && lks.active_request == null && lks.last_ok_time > 0 ){
					
					long elapsed = now - lks.last_ok_time;
					
					if ( elapsed > DHTControl.ORIGINAL_REPUBLISH_INTERVAL_DEFAULT - REPUBLISH_CHECK_PERIOD - PUT_TIME ){
						
							// always republish, we use put-and-forget
								
						ProxyLocalRequest last = lks.last_ok_request;
						
							// should always be a put op but whatever
						
						if ( last instanceof ProxyLocalRequestPut ){
							
							ProxyLocalRequestPut	old_put = (ProxyLocalRequestPut)last;
							
							ProxyLocalRequestPut	new_put = 
								new ProxyLocalRequestPut( 
									old_put.getKey(), 
									old_put.getValue(),
									new ProxyRequestAdapter(){
										
										@Override
										public void 
										complete(
											byte[] 	key, 
											boolean timeout)
										{
											log( "proxyPut republish complete" );
										}
									});
							
							lks.pending_request = new_put;
							
							proxy_requests.add( new_put );
							
							proxy_requests_sem.release();
							
							added = true;
						}
					}
				}
			}
			
			if ( added ){
				
				checkRequestDispatcher();
			}
		}
	}
	
	private void
	checkRequestTimeouts()
	{
		long now_mono = SystemTime.getMonotonousTime();
		
		OutboundConnectionProxy	proxy;
		
		List<ProxyLocalRequest>	failed = new ArrayList<>();
		
		synchronized( proxy_requests ){
			
			proxy = active_client_proxy;
			
			Iterator<ProxyLocalRequest>	it = proxy_requests.iterator();
			
			while( it.hasNext()){
				
				ProxyLocalRequest request = it.next();
				
				if ( request.getType() == ProxyLocalRequest.RT_GET ){
					
					ProxyLocalRequestGet get = (ProxyLocalRequestGet)request;
					
						// inactive requests get a shorter timeout as there is no usable
						// proxy
					
					long timeout = Math.min( get.getTimeout(), 60*1000 );
					
					if ( now_mono - get.getStartTimeMono() > timeout ){
						
						failed.add( request );
						
						it.remove();
					}
				}
			}
		}
		
		for ( ProxyLocalRequest r: failed ){
			
			requestFailed( proxy, r );
		}
		
		if ( proxy != null ){
			
			proxy.checkRequestTimeouts();
		}
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
								
								ProxyLocalRequest 			request;
								
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
										
										if ( request.getType() != ProxyLocalRequest.RT_GET ){
											
											byte[] key = request.getKey();
											
											LocalKeyState	lks = local_key_state.get( key );
											
											if ( lks == null ){
												
												Debug.out( "lks should always exist" );
												
											}else{
												
												lks.pending_request = null;
												
												if ( lks.active_request != null ){
													
													Debug.out( "lks shouldn't have an active request" );
													
												}else{
													
													lks.active_request = request;
												}
											}
										}
									}
								}
								
								if ( request != null ){
									
									proxy.addRequest( 
										request,
										new ActiveRequestListener()
										{
											private AtomicBoolean done = new AtomicBoolean();
											
											@Override
											public void 
											complete(
												OutboundConnectionProxy		proxy,
												ProxyLocalRequest			request )
											{
												if ( !done.compareAndSet( false, true )){
													
													return;
												}
												
												requestComplete( proxy, request );
											}
											
											@Override
											public void 
											failed(
												OutboundConnectionProxy		proxy,
												ProxyLocalRequest			request )
											{
												if ( !done.compareAndSet( false, true )){
													
													return;
												}
												
												requestFailed( proxy, request );
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
		
		if ( proxy_client_consec_fail_count > 5 && !force ){
			
				// fall back to periodic attempts rather than immediate retries
			
			return;
		}
		
		OutboundConnectionProxy close_if_idle = null;
		
		synchronized( connections ){
			
			if ( current_client_proxy != null && !current_client_proxy.isClosed()){
				
				if ( current_client_proxy.isTired()){			
				
					close_if_idle = current_client_proxy;
					
				}else{
					
					return;
				}
			}
			
			if ( checking_client_proxy ){
				
				client_proxy_check_outstanding = true;
				
				return;
			}
			
			checking_client_proxy = true;
		}
		
		if ( close_if_idle != null ){
		
			close_if_idle.closeIfIdle();
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
				
			}else{
				
				// sp.failed( new Exception("test close"));
				
				sp.checkRequests();
			}
		}
	}
	
	private boolean
	checkClientProxySupport()
	{
		if ( destroyed || closing_down ){
			
			return( false );
		}
		
		String local_mix_host	= tep_mix.getHost();
		String local_pure_host	= tep_pure.getHost();
		
		if ( local_mix_host == null || local_pure_host == null ){
			
			return( false );
		}
		
		if ( !ddb.isInitialized()){
			
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

			int contact_version = contact.getVersion();
			
			if ( contact_version == 0 || contact_version >= MIN_SERVER_VERSION || LOOPBACK ){
			
				if ( tryClientProxy( local_mix_host, target )){
				
					return( true );
				}
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
			
				target = InetSocketAddress.createUnresolved( "oq3qxj3calntdzn54vqcsshertvbxcwdr56jrufim4jojjvrrdyqlfyd.onion", 27657);
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
		plugin.log( "Tor proxy DHT initialised, id=" + instance_id + ", cid=" + proxy.getConnectionID());
		
		synchronized( proxy_client_fail_map ){
			
			proxy_client_consec_fail_count = 0;
			
			proxy_client_get_fails = 0;
			
			proxy_client_fail_map.remove( proxy.getHost());
			
			failed_client_proxy = null;
			
			failed_client_proxy_retry_count = 0;
		}
		
		synchronized( proxy_requests ){
		
			if ( !proxy_client_fail_uids.containsKey( proxy.getLocalUID())){
				
				active_client_proxy = proxy;
				
				checkRequestDispatcher();
			}
		}
	}
	
	private void
	proxyClientFailed(
		OutboundConnectionProxy		proxy )
	{
		plugin.log( "Tor proxy DHT failed, id=" + proxy.getRemoteInstanceID() + ", cid=" + proxy.getConnectionID());
		
		synchronized( proxy_requests ){
	
			proxy_client_fail_uids.put( proxy.getLocalUID(), "" );
			
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
					
					if ( cp.isPermanentFailure()){
						
							// don't set up retry
						
						failed_client_proxy				= null;
						failed_client_proxy_retry_count	= 0;
						
					}else{
						
						if ( failed_client_proxy == null || !failed_client_proxy.equals( ias )){
							
							failed_client_proxy = ias;
							
							failed_client_proxy_retry_count = 0;
						}
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
	
	
	private void
	maskValue(
		byte[]		key,
		byte[]		masked_value )
	
		throws Exception
	{
		int	pos = 0;
		
		MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
		
		for ( int i=0;pos<masked_value.length;i++){
			
			sha256.update( "TorProxyDHT::mask".getBytes( Constants.UTF_8 ));
			
			sha256.update((byte)i);
			
			sha256.update( key );
			
			byte[] value_mask = sha256.digest();
			
			for ( int m=0;pos<masked_value.length&&m<value_mask.length;pos++,m++){
				
				masked_value[pos] ^= value_mask[m];
			}
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
		
			plugin.log( str );
			
			System.out.println( str );
		}
	}
	
	private static final AtomicInteger		conn_id_next = new AtomicInteger();

	private abstract class
	Connection
		implements GenericMessageConnectionListener, GenericMessageConnectionPropertyHandler
	{	
		private final int	connection_id	= conn_id_next.incrementAndGet();
		
		private final long	start_time		= SystemTime.getCurrentTime();
		private final long	start_time_mono = SystemTime.getMonotonousTime();
		
		private GenericMessageConnection	gmc;
		
		private long	last_received_time_mono	= start_time_mono;
		private long	last_sent_time_mono		= start_time_mono;
				
		private long	disconnect_after_mono	= -1;
		
		private volatile boolean	connected;
		
		private volatile boolean failed;
		private volatile boolean permanent_failure;
		
		Connection()
		{
			log( getName() + ": created" );
		}
		
		protected int
		getConnectionID()
		{
			return( connection_id );
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
		log(
			String		str )
		{
			TorProxyDHT.this.log( connection_id + ": " + str );
		}
		
		protected void
		setConnected()
		{
			if ( this instanceof OutboundConnection ){
			
				log( getName() + ": connected (" + ( SystemTime.getMonotonousTime() - start_time_mono )/1000 + "s)");
			}
			
			connected	= true;
		}
		
		private void
		timerTick(
			long	now_mono )
		{
			if ( disconnect_after_mono >= 0 && now_mono > disconnect_after_mono ){
				
				failed( new Exception( "Force disconnect" ));
				
				return;
			}
			
			if ( !connected ){
				
				return;
			}
			
			if ( now_mono - last_received_time_mono > 120*1000 ){
				
				failed( new Exception( "Inactivity timeout" ));
				
			}else if ( now_mono - last_sent_time_mono >= 35*1000 + RandomUtils.nextInt(10*1000 )){
				
				SimpleTimer.addEvent(
					"KeepAlive",
					SystemTime.getOffsetTime( RandomUtils.nextInt(10*1000 )),
					(ev)->{
						if ( !failed ){
							Map<String,Object> map = new HashMap<>();
										
							send( MT_PROXY_KEEP_ALIVE, map );
						}
					});
			}
		}
		
		protected int
		getAgeSeconds()
		{
			return((int)((SystemTime.getCurrentTime() - start_time)/1000));
		}
		
		protected void
		setDisconnectAfterSeconds(
			int		secs )
		{
			if ( secs < 0 ){
				
				disconnect_after_mono = -1;
				
			}else{
				
				disconnect_after_mono = SystemTime.getMonotonousTime() + secs*1000;
			}
		}
		
		protected void
		send(
			int						type,
			Map<String,Object>		map )
		{
			last_sent_time_mono	= SystemTime.getMonotonousTime();
			
			map.put( "type", type );
			
			map.put( "ver", I2PHelperAltNetHandlerTor.LOCAL_VERSION );

			if ( type != MT_PROXY_KEEP_ALIVE ){
				
				log( "send " + map );
			}

			map.put( "pad", new byte[1+RandomUtils.nextInt(128)]);

			PooledByteBuffer buffer = null;
			
			try{
				buffer = plugin_interface.getUtilities().allocatePooledByteBuffer( BEncoder.encode(map));
				
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
			last_received_time_mono = SystemTime.getMonotonousTime();
					
			try{
				Map<String,Object> map = BDecoder.decode( message.toByteArray());
					
				map.remove( "pad" );
				
				int	type = ((Number)map.get( "type" )).intValue();
				
				if ( type == MT_PROXY_CLOSE ){
					
					disconnect_after_mono = 0;
					
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
			int						type,
			Map<String,Object>		map )

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
			
				Map<String,Object> map = new HashMap<>();
				
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
			failed( error, false );
		}
		
		protected void
		failed(
			Throwable 	error,
			boolean		perm_fail )
		{
			synchronized( this ){
				
				if ( failed ){
					
					return;
				}
				
				failed 				= true;
				permanent_failure	= perm_fail;
			}
			
			try{
				if ( disconnect_after_mono == -1 ){
				
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
		
		protected boolean
		isPermanentFailure()
		{
			return( permanent_failure );
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
			long now_mono = SystemTime.getMonotonousTime();
			
			return( getName() + ", idle_in=" + (now_mono - last_received_time_mono)/1000 + "s, idle_out=" + (now_mono - last_sent_time_mono)/1000 + "s");
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
		
		private final int		max_age_secs		= 2*60*60 + RandomUtils.nextInt( 60*60 );
		private final String	tep_pure_host;
		private final int		tep_pure_port;
		private final byte[]	tep_pure_host_bytes;	// prefer to use pk but sha3_256 not in Java 8 ...
		
		private final String	local_uid;
		private String			remote_iid;
		
		private volatile int state	= STATE_INITIALISING;
		
		private volatile boolean	has_been_active;
		
		private Map<Long,ActiveRequest>		active_requests = new HashMap<>();
		
		private boolean closing_on_idle;
		
		private
		OutboundConnectionProxy(
			InetSocketAddress		target )
		
			throws Exception
		{
			super( target );
				
			tep_pure_host	= tep_pure.getHost();
			tep_pure_port	= tep_pure.getPort();
			
			tep_pure_host_bytes		= I2PHelperPlugin.TorEndpoint.onionToBytes( tep_pure_host );
			
			byte[] _uid = new byte[32];
			
			RandomUtils.nextSecureBytes(_uid);
						
			local_uid = Base32.encode(_uid);
		}
		
		@Override
		protected String
		getName()
		{
			return( "Proxy out" );
		}
		
		protected String
		getLocalUID()
		{
			return( local_uid );
		}
		
		protected String
		getRemoteInstanceID()
		{
			return( remote_iid );
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
		
		protected boolean
		isTired()
		{
			return( getAgeSeconds() > max_age_secs );
		}
		
		protected void
		closeIfIdle()
		{
			synchronized( this ){
				
				if ( active_requests.isEmpty()){
					
					closing_on_idle = true;
				}
			}
			
			if ( closing_on_idle ){
				
				failed( new Exception( "Proxy is tired" ), true );
			}
		}
		
		protected void
		addRequest(
			ProxyLocalRequest			request,
			ActiveRequestListener	listener )
		{
			ActiveRequest	ar;
			
			synchronized( this ){
				
				if ( state == STATE_ACTIVE && !closing_on_idle ){
					
					ar = new ActiveRequest( request, listener );
					
					active_requests.put( request.getSequence(), ar );
					
				}else{
					
					ar = null;
				}
			}
			
			if ( ar == null ){
				
				listener.failed( this, request );
				
			}else{
										
				try{
					byte[]	key		= request.getKey();

					MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
					
					sha256.update( "TorProxyDHT::key".getBytes( Constants.UTF_8 ));
					
					sha256.update( key );
					
					byte[] derived_key = sha256.digest();
					
					Map<String,Object> proxy_request = new HashMap<>();

					proxy_request.put( "op_key", derived_key );
					proxy_request.put( "op_seq", request.getSequence());
					
					Map<String,Object> options = request.getOptions();
					
					if ( options != null && !options.isEmpty()){
						
						proxy_request.put( "op_options", options);
					}

					if ( request.getType() == ProxyLocalRequest.RT_PUT ){
						
						Map<String,Object> value = ((ProxyLocalRequestPut)request).getValue();
						
						value = new HashMap<>( value );	// copy as we add to it
						
						value.put( "h", tep_pure_host_bytes );
						value.put( "p", tep_pure_port );

						byte[] bytes = BEncoder.encode(value);
						
						byte[] sig = tep_pure.sign( bytes );
												
						value.put( "z", sig );
				
						value.put( "t", Math.max( SystemTime.getCurrentTime()/1000 - 1600000000L + time_skew, 0L )); // Y2038 problem? :)
						
						byte[] masked_value = BEncoder.encode( value );
						
						maskValue( key, masked_value );
												
						proxy_request.put( "op_type", PROXY_OP_PUT );
						proxy_request.put( "op_value", masked_value );
											
					}else if ( request.getType() == ProxyLocalRequest.RT_GET ){
						
						proxy_request.put( "op_type", PROXY_OP_GET );
						
					}else if ( request.getType() == ProxyLocalRequest.RT_REMOVE ){
						
						proxy_request.put( "op_type", PROXY_OP_REMOVE );
						
					}else{
						
						throw( new Exception( "eh?" ));
					}
					
					send( MT_PROXY_OP_REQUEST, proxy_request );

				}catch( Throwable e ){
					
					Debug.out( e );
					
					synchronized( this ){
						
						active_requests.remove( request.getSequence());
					}
					
					ar.setFailed();
				}
			}
		}
		
		protected void
		checkRequestTimeouts()
		{
			long now_mono = SystemTime.getMonotonousTime();
			
			List<ActiveRequest>	failed = new ArrayList<>();
			
			synchronized( this ){
			
				Iterator<ActiveRequest>	it = active_requests.values().iterator();
				
				while( it.hasNext()){
					
					ActiveRequest ar = it.next();
					
					ProxyLocalRequest request = ar.getProxyRequest();
					
					if ( request.getType() == ProxyLocalRequest.RT_GET ){
						
						ProxyLocalRequestGet get = (ProxyLocalRequestGet)request;
						
						if ( now_mono - get.getStartTimeMono() > get.getTimeout()){
														
							failed.add( ar );
							
							it.remove();
						}						
						
					}
				}
			}
			
			for ( ActiveRequest ar: failed ){
				
				ar.setFailed();
			}
		}
		
		@Override
		public void 
		connected(
			GenericMessageConnection connection )
		{
			setConnected();
			
			try{
				Map<String,Object> payload = new TreeMap<>();
				
				payload.put( "source_host", tep_pure_host );
				payload.put( "source_port", tep_pure_port );
				payload.put( "target", getHost());
				payload.put( "uid", local_uid );
				
				byte[] bytes = BEncoder.encode(payload);
				
				byte[] sig = tep_pure.sign( bytes );
				
				Map<String,Object> map = new HashMap<>();
					
				map.put( "payload", payload );
				map.put( "sig", sig );
				
				map.put( "min_ver", MIN_SERVER_VERSION );
				
				send( MT_PROXY_ALLOC_REQUEST, map );
				
			}catch( Throwable  e ){
				
				failed( e );
			}
		}
				
		@Override
		public void 
		receive(
			int					type,
			Map<String,Object>	map )
		
			throws Exception
		{
			if ( type == MT_PROXY_ALLOC_OK_REPLY ){
				
				if ( getState() == STATE_INITIALISING ){
					
					map = BDecoder.decodeStrings( map );
					
					remote_iid = (String)map.get( "iid" );
					
					setState( STATE_ACTIVE );
					
					log( "Proxy client setup complete: iid=" + remote_iid );
					
					proxyClientSetupComplete( this, remote_iid );
				}
			}else if ( type == MT_PROXY_ALLOC_FAIL_REPLY ){
				
				map = BDecoder.decodeStrings( map );
				
				List<Map<String,Object>> contacts = (List<Map<String,Object>>)map.get( "contacts" );
				
				List<InetSocketAddress> isas = new ArrayList<>();
				
				for ( Map<String,Object> m: contacts ){
					
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
					
					// ignore, request might have timed out
					
				}else{
				
					try{
						ProxyLocalRequest request = ar.getProxyRequest();
						
						int request_type = request.getType();
						
						if ( request_type == ProxyLocalRequest.RT_GET ){
							
							long offset_time = Math.max( SystemTime.getCurrentTime()/1000 - 1600000000L, 0L );
							
							List<byte[]> l_values = (List<byte[]>)map.get( "op_values" );
								
							Map<String, Object[]> value_map = new HashMap<>();
							
							for ( byte[] masked_value: l_values ){
								
								maskValue( request.getKey(), masked_value );
								
								Map<String,Object> value = BDecoder.decode( masked_value );
								
								byte[]	sig = (byte[])value.remove( "z" );
								
								Number n_time = (Number)value.remove( "t" );
								
								int time = n_time==null?0:n_time.intValue();
								
								byte[]	host_bytes	= (byte[])value.get( "h" );
								
								int host_port = ((Number)value.get( "p" )).intValue();
								
								PublicKey source_pk = I2PHelperPlugin.TorEndpoint.getPublicKey( host_bytes );
								
								byte[] value_bytes = BEncoder.encode( value );
			
								if ( host_port != tep_pure_port ){
									
									log( "value port incorrect, ignoring" );
									
								}else if ( I2PHelperPlugin.TorEndpoint.verify( source_pk, value_bytes, sig )){
									
										// don't remove "h" as some uses rely on it, replace with
										// actual host
									
									String host = I2PHelperPlugin.TorEndpoint.bytesToOnion( host_bytes );
									
									if ( time > 0 && offset_time - time > 2 * DHTControl.ORIGINAL_REPUBLISH_INTERVAL_DEFAULT/1000 ){
										
										// log( "value time too ancient (" + TimeFormatter.formatColon( offset_time - time ) + "), host=" + host + ", ignoring" );

									}else{
										
										value.put( "h", host );
										
										Object[] existing = value_map.get( host );
										
										boolean add = false;
										
										if ( existing != null ){
											
											if (((Integer)existing[0]) < time ){
												
												add = true;
											}
										}else{
											
											add = true;
										}
										
										if ( add ){
											
											value_map.put( host, new Object[]{ time, value });
										}
									}
								}else{
									
									log( "value verification failed, ignoring" );
								}
							}
							
							
							List<Map<String,Object>> values = new ArrayList<>(value_map.size());

							for ( Object[] entry: value_map.values()){
								
								values.add((Map<String,Object>)entry[1]);
							}
							
							((ProxyLocalRequestGet)request).setValues( values );
						}
						
						ar.setComplete();
						
					}catch( Throwable e ){
						
						Debug.out( e );
					}
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
					ar.setFailed();
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
		}
		
		@Override
		protected String
		getString()
		{
			return( super.getString() + "; state=" + getState() + "; age=" + TimeFormatter.formatColon(getAgeSeconds()));
		}
		
		private class
		ActiveRequest
		{
			final ProxyLocalRequest			request;
			final ActiveRequestListener	listener;
			
			private AtomicBoolean done = new AtomicBoolean();
			
			ActiveRequest(
				ProxyLocalRequest			_request,
				ActiveRequestListener	_listener )
			{
				request 	= _request;
				listener	= _listener;
			}
			
			protected ProxyLocalRequest
			getProxyRequest()
			{
				return( request );
			}
			
			protected void
			setFailed()
			{
				if ( !done.compareAndSet( false, true )){
					
					return;
				}
				
				
				listener.failed( OutboundConnectionProxy.this, request );
			}
			
			protected void
			setComplete()
			{
				if ( !done.compareAndSet( false, true )){
					
					return;
				}
				
				listener.complete( OutboundConnectionProxy.this, request );
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
				
				Map<String,Object> map = new HashMap<>();
				
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
			int					type,
			Map	<String,Object>	map )
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
	
	public interface
	ProxyRemoteRequestListener
	{
		public default void
		complete()
		{
			complete( null );
		}
		
		public void
		complete(
			List<byte[]>	values );
	}

	private abstract class
	ProxyRemoteRequest
	{
		public static final int RT_PUT			= 1;
		public static final int RT_GET			= 2;
		public static final int RT_REMOVE		= 3;

		private final long							create_time_mono = SystemTime.getMonotonousTime();
		
		private final InboundConnectionProxy		proxy;
		private final int							type;
		private final byte[]						key;
		private final ProxyRemoteRequestListener	listener;
		
		private AtomicBoolean	done = new AtomicBoolean();
		
		protected
		ProxyRemoteRequest(
			InboundConnectionProxy		_proxy,
			int							_type,
			byte[]						_key,
			ProxyRemoteRequestListener	_listener )
		{
			proxy		= _proxy;
			type		= _type;
			key			= _key;
			listener	= _listener;
		}
		
		protected InboundConnectionProxy
		getProxy()
		{
			return( proxy );
		}
		
		protected int
		getType()
		{
			return( type );
		}
		
		protected long
		getCreateTimeMono()
		{
			return( create_time_mono );
		}
		
		protected byte[]
		getKey()
		{
			return( key );
		}
		
		protected abstract void
		execute();
		
		protected void
		requestComplete()
		{
			requestComplete( null );
		}
			
		protected void
		requestComplete(
			List<byte[]> 	values )
		{
			if ( !done.compareAndSet( false, true )){
				
				return;
			}

			try{
				listener.complete( values );
				
			}catch( Throwable e ){
				
				Debug.out( e );
			}
			
			proxy.remoteRequestComplete( this );
		}
		
		protected void
		requestFailed()
		{
			if ( !done.compareAndSet( false, true )){
				
				return;
			}

			try{
				listener.complete();
				
			}catch( Throwable e ){
				
				Debug.out( e );
			}
			
			proxy.remoteRequestComplete( this );
		}
	}
	
	private final ByteArrayHashMap<Long>	dht_key_state = new ByteArrayHashMap<>();
		
	private final LinkedList<DHTRemoteRequest>[]		dht_remote_queued = new LinkedList[2];
	private final ByteArrayHashMap<DHTRemoteRequest>[]	dht_remote_active = new ByteArrayHashMap[2];
	
	{
		dht_remote_queued[0] = new LinkedList<>();
		dht_remote_queued[1] = new LinkedList<>();
		
		dht_remote_active[0] = new ByteArrayHashMap<>();
		dht_remote_active[1] = new ByteArrayHashMap<>();
	}
	
	private final int[] DHT_REMOTE_MAX_ACTIVE_REQUESTS = { 16, 16 };

	private void
	printGlobalKeyState()
	{
		long now = SystemTime.getCurrentTime();

		synchronized( dht_key_state ){
			
			String str = "GKS size=" + dht_key_state.size() + 
					", active=" + dht_remote_active[0].size() + "/" + dht_remote_active[1].size() +
					", queued=" + dht_remote_queued[0].size() + "/" + dht_remote_queued[1].size();
			
			log( str );
			
			ByteArrayHashMap<String>	type_map = new ByteArrayHashMap<>();
							
			for ( DHTRemoteRequest req: dht_remote_active[0].values()){
				
				type_map.put( req.getMaskedKey(), ", get" );
			}
			
			for ( DHTRemoteRequest req: dht_remote_active[1].values()){
				
				type_map.put( req.getMaskedKey(), ", put" );
			}
			
			for ( byte[]key: dht_key_state.keys()){
				
				long time = dht_key_state.get( key );
							
				long elapsed = now - time;

				String age_str = "age=" + TimeFormatter.formatColon(elapsed/1000);
					
				String act_str = type_map.get( key );
				
				if ( act_str == null ){
					
					act_str = "";
				}

				log( "    " + ByteFormatter.encodeString(key,0,Math.min(key.length,8)) + " -> " + age_str + act_str );
			}
		}
	}
	
	private void
	checkGlobalKeyState()
	{
		long now = SystemTime.getCurrentTime();
		
		synchronized( dht_key_state ){
			
			for ( byte[] key: dht_key_state.keys()){
				
				long elapsed = now - dht_key_state.get( key );
				
				if ( elapsed > DHTControl.ORIGINAL_REPUBLISH_INTERVAL_DEFAULT ){

					dht_key_state.remove( key );
				}
			}
		}
	}
	
	private abstract class
	DHTRemoteRequest
	{
		protected static final int OP_TYPE_GET		= 0;
		protected static final int OP_TYPE_PUT_REM	= 1;
		
		protected final byte[]		masked_key;
		protected final byte[]		op_key;
		
		protected final int			type;
		
		protected final List<ProxyRemoteRequest>			requests = new ArrayList<>();
		protected final List<ProxyRemoteRequestListener>	listeners = new ArrayList<>();
		
		DHTRemoteRequest(
			ProxyRemoteRequest			_request,
			ProxyRemoteRequestListener	_listener,
			byte[]						_masked_key,
			byte[]						_op_key,
			int							_type )
		{
			masked_key	= _masked_key;
			op_key		= _op_key;

			type		= _type;
			
			requests.add( _request );
			listeners.add( _listener );
		}
		
		protected byte[]
		getMaskedKey()
		{
			return( masked_key );
		}
		
		private void
		addRequest(
			DHTRemoteRequest	request )
		{
			requests.addAll( request.requests );
			
			listeners.addAll( request.listeners );
		}
		
		private boolean
		isDead()
		{
			for ( ProxyRemoteRequest req: requests ){
				
				if ( req.getProxy().getState() == InboundConnectionProxy.STATE_ACTIVE ){
					
					return( true );
				}
			}
			
			return( false );
		}
		
		protected void
		queue()
		{
			boolean request_queued;
			
			LinkedList<DHTRemoteRequest>		queued = dht_remote_queued[ type ];
			ByteArrayHashMap<DHTRemoteRequest>	active = dht_remote_active[ type ];

			synchronized( dht_key_state ){
				
				if ( dht_key_state.size() > MAX_GLOBAL_KEY_STATE ){
					
					request_queued = false;
					
				}else{
					
					request_queued = true;
					
					DHTRemoteRequest active_request = active.get( op_key );
					
					if ( active_request != null ){
						
						active_request.addRequest( this );
						
					}else{
						
						if ( active.size() < DHT_REMOTE_MAX_ACTIVE_REQUESTS[ type ]){
							
							active.put( op_key, this );
													
							try{
								execute();
								
							}catch( Throwable e ){
								
								Debug.out( e );
							}
							
						}else{
							
							boolean done = false;
							
							Iterator<DHTRemoteRequest> it = queued.iterator();
							
							while( it.hasNext()){
								
								DHTRemoteRequest req = it.next();
								
								if ( req.isDead()){
									
									it.remove();
									
								}else{
									
									if ( Arrays.equals( req.op_key, op_key )){
										
										req.addRequest( this );
										
										done = true;
										
										break;
									}
								}
							}
							
							if ( !done ){
								
								queued.addLast( this );
							}
						}
					}
				}
			}
			
			if ( !request_queued ){
				
				listeners.get(0).complete( new ArrayList<>(0));
			}
		}
		
		protected void
		completed()
		{
			completed( null );
		}
		
		protected void
		completed(
			List<byte[]>	values )
		{
			LinkedList<DHTRemoteRequest>		queued = dht_remote_queued[ type ];
			ByteArrayHashMap<DHTRemoteRequest>	active = dht_remote_active[ type ];

			List<ProxyRemoteRequest>			todo_requests;
			List<ProxyRemoteRequestListener>	todo_listeners;
			
			synchronized( dht_key_state ){
		
				todo_requests	= new ArrayList<>( requests );
				todo_listeners	= new ArrayList<>( listeners );

				active.remove( op_key );
										
				while( !queued.isEmpty()){
					
					try{
						DHTRemoteRequest next = queued.removeFirst();
						
						if ( next.isDead()){
							
							continue;
						}
						
						active.put( next.op_key, next );
						
						next.execute();
						
					}catch( Throwable e ){
						
						Debug.out( e );
					}
				}
			}
				
			for ( int i=0; i<todo_requests.size(); i++ ){
				
				if ( todo_requests.get(i).getProxy().getState() == InboundConnectionProxy.STATE_ACTIVE ){
					
					try{
						if ( values == null ){

							todo_listeners.get(i).complete();
							
						}else{
							
							todo_listeners.get(i).complete( values );
						}
					}catch( Throwable e ){
						
						Debug.out( e );
					}
				}
			}
		}
		
		protected abstract void
		execute();
	}
	
	private class
	DHTRemoteRequestPut
		extends DHTRemoteRequest
	{
		private final byte[]		value;
		private final byte			flags;
		
		DHTRemoteRequestPut(
			ProxyRemoteRequest			_request,
			ProxyRemoteRequestListener	_listener,
			byte[]						_masked_key,
			byte[]						_op_key,
			byte[]						_value,
			byte						_flags )
		{
			super( _request, _listener, _masked_key, _op_key, OP_TYPE_PUT_REM );
			
			value			= _value;
			flags			= _flags;
			
			queue();
		}
		
		protected void
		execute()
		{
			DHTPluginInterface dht = ddb.getDHTPlugin();
			
			synchronized( dht_key_state ){

				dht_key_state.put( masked_key, SystemTime.getCurrentTime());
			}
			
			DHTPluginOperationListener listener = 
				new DHTPluginOperationAdapter()
				{
					@Override
					public void 
					complete(
						byte[]		masked_key, 
						boolean		timeout_occurred )
					{
						synchronized( dht_key_state ){
	
							dht_key_state.put( masked_key, SystemTime.getCurrentTime());
						}
						
						completed();
					}
				};
			
			boolean done = false;
				
			if ( method_DHTPluginInterface_put != null ){
				
				short s_flags = (short)( flags | 0x0100 ); // DHTPluginInterface.FLAG_PUT_AND_FORGET
				
				try{
					method_DHTPluginInterface_put.invoke(
						dht,
						masked_key, 
						"TPD write: " + ByteFormatter.encodeString( masked_key ), 
						value, 
						s_flags, 
						true,
						listener );
					
					done = true;
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
			
			if ( !done ){
				
				dht.put( 
					masked_key , 
					"TPD write: " + ByteFormatter.encodeString( masked_key ), 
					value, 
					flags, 
					listener );
			}
		}
	}
	
	private class
	DHTRemoteRequestGet
		extends DHTRemoteRequest
	{
		private final byte			flags;
		private final int			num_want;
		private final long			timeout;
		
		DHTRemoteRequestGet(
			ProxyRemoteRequest			_request,
			ProxyRemoteRequestListener	_listener,
			byte[]						_masked_key,
			byte[]						_op_key,
			byte						_flags,
			int							_num_want,
			long						_timeout )
		{
			super( _request, _listener, _masked_key, _op_key, OP_TYPE_GET );
			
			flags			= _flags;
			num_want		= _num_want;
			timeout			= _timeout;
			
			queue();
		}
		
		protected void
		execute()
		{
			DHTPluginInterface dht = ddb.getDHTPlugin();
			
			dht.get(
					masked_key, 
					"TPD read: " + ByteFormatter.encodeString(masked_key), 
					flags,
					num_want,
					timeout,
					false, false,
					new DHTPluginOperationAdapter()
					{
						ByteArrayHashMap<String>	result = new ByteArrayHashMap<>();
						
						@Override
						public void 
						valueRead(
							DHTPluginContact	originator, 
							DHTPluginValue		value )
						{
							synchronized( result ){
							
								result.put( value.getValue(), "");
							}
						}
						
						@Override
						public void 
						complete(
							byte[]		masked_key, 
							boolean		timeout_occurred )
						{
							List<byte[]> res;
							
							synchronized( result ){
								
								 res = new ArrayList<byte[]>( result.keys());
							}
							
							completed( res );
						}
					});
		}
	}
	
	private class
	DHTRemoteRequestRemove
		extends DHTRemoteRequest
	{
		DHTRemoteRequestRemove(
			ProxyRemoteRequest			_request,
			ProxyRemoteRequestListener	_listener,
			byte[]						_masked_key,
			byte[]						_op_key )
		{
			super( _request, _listener, _masked_key, _op_key, OP_TYPE_PUT_REM );
						
			queue();
		}
		
		protected void
		execute()
		{
			DHTPluginInterface dht = ddb.getDHTPlugin();

			boolean done = false;
			
			DHTPluginOperationListener listener = 
				new DHTPluginOperationAdapter()
				{
					@Override
					public void 
					complete(
						byte[]		masked_key, 
						boolean		timeout_occurred )
					{
						synchronized( dht_key_state ){

							dht_key_state.remove( masked_key );
						}
						
						completed();
					}
				};	
					
			if ( method_DHTPluginInterface_remove != null ){
				
				short s_flags = 0x0100; // DHTPluginInterface.FLAG_PUT_AND_FORGET
				
				try{
					method_DHTPluginInterface_remove.invoke(
						dht,
						masked_key, 
						"TPD write: " + ByteFormatter.encodeString( masked_key ), 
						s_flags, 
						listener );
					
					done = true;
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
			
			if ( !done ){			
			
				dht.remove( 
					masked_key, 
					"TPD remove: " + ByteFormatter.encodeString(masked_key), 
					listener );
			}
		}
	}
	
	private void
	executeRemotePut(
		ProxyRemoteRequest			request,
		byte[]						masked_key,
		byte[]						value,
		byte						flags,
		ProxyRemoteRequestListener	listener )
	{
		SHA1Hasher sha1 = new SHA1Hasher();
		
		sha1.update( "Put".getBytes());
		sha1.update( masked_key );
		sha1.update( value );
		sha1.update(new byte[]{ flags });
		
		byte[] op_key = sha1.getDigest();
		
		new DHTRemoteRequestPut( request, listener, masked_key, op_key, value, flags );
	}
	
	private void
	executeRemoteGet(
		ProxyRemoteRequest			request,
		byte[]						masked_key,
		byte						flags,
		int							num_want,
		long						timeout,
		ProxyRemoteRequestListener	listener )
	{
		SHA1Hasher sha1 = new SHA1Hasher();
		
		sha1.update( "Get".getBytes());
		sha1.update( masked_key );
		sha1.update( new byte[]{ flags });
		//sha1.update( String.valueOf(num_want).getBytes());
		//sha1.update( String.valueOf(timeout).getBytes());
	
		byte[] op_key = sha1.getDigest();
		
		new DHTRemoteRequestGet( request, listener, masked_key, op_key, flags, num_want, timeout );
	}
	
	private void
	executeRemoteRemove(
		ProxyRemoteRequest			request,
		byte[]						masked_key,
		ProxyRemoteRequestListener	listener )
	{
		SHA1Hasher sha1 = new SHA1Hasher();
		
		sha1.update( "Rem".getBytes());
		sha1.update( masked_key );
		
		byte[] op_key = sha1.getDigest();
		
		new DHTRemoteRequestRemove( request, listener, masked_key, op_key );
	}
	
	private class
	ProxyRemoteRequestPut
		extends ProxyRemoteRequest
	{
		private final byte[]				value;
		private final Map<String,Object>	options;
		
		protected
		ProxyRemoteRequestPut(
			InboundConnectionProxy		proxy,
			byte[]						key,
			byte[]						_value,
			Map<String,Object>			_options,
			ProxyRemoteRequestListener	listener )
		{
			super( proxy, RT_PUT, key, listener );
			
			value	= _value;
			options	= _options;
		}
		
		protected void
		execute()
		{
			try{
				byte	flags = DHTPluginInterface.FLAG_SINGLE_VALUE | DHTPluginInterface.FLAG_ANON;
				
				if ( options != null ){
					
					Number f = (Number)options.get( "f" );
					
					if ( f != null ){
						
						byte opt_f = f.byteValue();
						
						opt_f &= ( DHTPluginInterface.FLAG_SEEDING | DHTPluginInterface.FLAG_DOWNLOADING );
						
						flags |= opt_f;
					}
				}
				
				byte[] original_key = getKey();
								
				MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
				
				sha256.update( "TorProxyDHT::remote_mask".getBytes( Constants.UTF_8 ));
				
				sha256.update( original_key );
				
				byte[] masked_key = sha256.digest();

				getProxy().addKeyState( original_key );

				executeRemotePut( this, masked_key, value, flags, (values)->requestComplete());
				
			}catch( Throwable  e ){
				
				Debug.out(e);
				
				requestFailed();
			}
		}
	}
	
	private class
	ProxyRemoteRequestGet
		extends ProxyRemoteRequest
	{
		private final Map			options;
		
		private long	timeout		= 2*60*1000;

		protected
		ProxyRemoteRequestGet(
			InboundConnectionProxy		proxy,
			byte[]						key,
			Map							_options,
			ProxyRemoteRequestListener	listener )
		{
			super( proxy, RT_GET, key, listener );
			
			options	= _options;
			
			if ( options != null ){
				
				Number t = (Number)options.get( "t" );
				
				if ( t != null ){
					
					long opt_timeout = t.intValue();
					
					timeout = Math.min( timeout, opt_timeout );
				}
			}
		}
		
		protected long
		getTimeout()
		{
			return( timeout );
		}
		
		protected void
		execute()
		{
			try{
				long queued = SystemTime.getMonotonousTime() - getCreateTimeMono();
				
				timeout -= queued;
				
				if ( timeout < 1000 ){
					
					requestFailed();
					
				}else{
										
					byte	flags		= 0;
					int		num_want	= 32;
					
					if ( options != null ){
						
						Number f = (Number)options.get( "f" );
						
						if ( f != null ){
							
							byte opt_f = f.byteValue();
							
							opt_f &= ( DHTPluginInterface.FLAG_SEEDING | DHTPluginInterface.FLAG_DOWNLOADING );
							
							flags |= opt_f;
						}
						
						Number n = (Number)options.get( "n" );
						
						if ( n != null ){
							
							int opt_num_want = n.intValue();
							
							if ( opt_num_want < 128 ){
								
								num_want = opt_num_want;
							}
						}
					}
						
					byte[] original_key = getKey();
					
					MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
					
					sha256.update( "TorProxyDHT::remote_mask".getBytes( Constants.UTF_8 ));
					
					sha256.update( original_key );
					
					byte[] masked_key = sha256.digest();

					executeRemoteGet( this, masked_key, flags, num_want, timeout, (values)->requestComplete( values ));
				}
			}catch( Throwable  e ){
				
				Debug.out(e);
				
				requestFailed();
			}
		}
	}
	
	private class
	ProxyRemoteRequestRemove
		extends ProxyRemoteRequest
	{
		protected
		ProxyRemoteRequestRemove(
			InboundConnectionProxy		proxy,
			byte[]						key,
			ProxyRemoteRequestListener	listener )
		{
			super( proxy, RT_REMOVE, key, listener );
		}
		
		protected void
		execute()
		{
			try{				
				byte[] original_key = getKey();
				
				MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
				
				sha256.update( "TorProxyDHT::remote_mask".getBytes( Constants.UTF_8 ));
				
				sha256.update( original_key );
				
				byte[] masked_key = sha256.digest();
				
				executeRemoteRemove( this, masked_key, (values)->{
					
					requestComplete();
					
					getProxy().removeKeyState( original_key );
				});
				
			}catch( Throwable  e ){
				
				Debug.out(e);
				
				requestFailed();
			}
		}
	}
	
	private final int MAX_SAVED_REMOTE_KEY_STATE = 16;
	
	private Map<String,Object[]>		saved_remote_key_state = 
			new LinkedHashMap<String,Object[]>(MAX_SAVED_REMOTE_KEY_STATE,0.75f,true)
			{
				@Override
				protected boolean
				removeEldestEntry(
			   		Map.Entry<String,Object[]> eldest) 
				{
					return size() > MAX_SAVED_REMOTE_KEY_STATE;
				}
			};
			
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

		private int			version;
		private int			min_version;
		
		private String		source_host;
		private PublicKey 	source_pk;
		
		
		private ByteArrayHashMap<String>	remote_key_state = new ByteArrayHashMap<>();

			// get requests at index 0, others index 1

		private LinkedList<ProxyRemoteRequest>[]	queued_requests = new LinkedList[2];

		{
			queued_requests[0] = new LinkedList<>();
			queued_requests[1] = new LinkedList<>();
		}

		private int[]	active_request_count	= { 0, 0 };

		private final int[] MAX_ACTIVE_REQUESTS = { 16, 16 };

		private final int MAX_QUEUED_REQUESTS	= 128;

	
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
		
		private List<Map>
		getBackupContacts()
		{
			List<Map> l_contacts = new ArrayList<>();

			DHTTransportAlternativeNetwork net = DHTUDPUtils.getAlternativeNetwork( DHTTransportAlternativeNetwork.AT_TOR );
			
			if ( net != null ){
			
				List<DHTTransportAlternativeContact> contacts = DHTUDPUtils.getAlternativeContacts( DHTTransportAlternativeNetwork.AT_TOR, 16 );

				Collections.shuffle( contacts );
					
				for ( DHTTransportAlternativeContact contact: contacts ){
												
					int contact_version = contact.getVersion();
					
					if ( contact_version == 0 || contact_version >= min_version ){
						
						Map m = new HashMap<>();
						
						InetSocketAddress isa = net.getNotionalAddress( contact );
						
						m.put( "host", AddressUtils.getHostAddress( isa ));
						m.put( "port", isa.getPort());
						
						l_contacts.add( m );								
						
						if ( l_contacts.size() == 5 ){
							
							break;
						}
					}
				}
			}
			
			return( l_contacts );
		}
		
		@Override
		protected void 
		receive(
			int 				type,
			Map<String,Object> 	request ) 
			
			throws Exception
		{
			if ( type == MT_PROXY_ALLOC_REQUEST ){
				
				if ( !ENABLE_PROXY_SERVER ){
					
					throw( new Exception( "Proxy server disabled" ));
				}
				
				Number i_ver = (Number)request.get( "ver" );
				
				version = i_ver==null?1:i_ver.intValue();
				
				Number i_min_ver = (Number)request.get( "min_ver" );

				min_version = i_min_ver==null?1:i_min_ver.intValue();
				
				Map<String,Object> payload_raw = (Map<String,Object>)request.get( "payload" );
												
				Map<String,Object> payload = BDecoder.decodeStrings( payload_raw );
				
				String target	= (String)payload.get( "target" );
				
				if ( target != null ){
					
					if ( !target.equals( tep_mix.getHost())){
					
						throw( new Exception( "target host mismatch" ));
					}
				}
				
				source_host	= (String)payload.get( "source_host" );
				
				boolean denied = false;
				
				if ( min_version > I2PHelperAltNetHandlerTor.LOCAL_VERSION ){
					
					denied = true;
					
				}else{
					
					InboundConnectionProxy dead_proxy = null;
					
					synchronized( connections ){
						
						if ( server_proxies.size() >= MAX_SERVER_PROXIES ){
		
							denied = true;
							
						}else{
						
							for ( InboundConnectionProxy sp: server_proxies ){
								
								if ( source_host.equals( sp.source_host )){
									
										// assume that the connection has failed on the existing 
										// proxy but we haven't noticed yet whereas the 
										// client has and is attempting to reconnect
									
									log( "Duplicate proxy for " + source_host + ", replacing" );
									
									server_proxies.remove( sp );
									
									dead_proxy = sp;
								}
							}
							
							server_proxies.add( this );
						}
					}
					
					if ( dead_proxy != null ){
						
						dead_proxy.failed( new Exception( "Dead Proxy" ));
					}
				}
				
				if ( denied ){
					
					Map<String,Object> reply = new HashMap<>();
															
					reply.put( "contacts", getBackupContacts());
					
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
				
				if ( getState() != STATE_ACTIVE ){
					
					throw( new Exception( "OP received when inactive" ));
				}
				
				int		op_type = ((Number)request.get( "op_type" )).intValue();
				long	seq		= ((Number)request.get( "op_seq" )).longValue();
				
				byte[] 	key		= (byte[])request.get( "op_key" );
				Map		options	= (Map)request.get( "op_options" );
				
				Map<String,Object> reply = new HashMap<>();

				reply.put( "op_seq", seq );

				switch( op_type ){
				
					case PROXY_OP_PUT:{
	
						byte[] value = (byte[])request.get( "op_value" );
						
						proxyRemotePut(
							key, value, options, (v)->{
								
								send( MT_PROXY_OP_REPLY, reply );
							});

																								
						break;
					}
					case PROXY_OP_GET:{
						
						proxyRemoteGet( key, options, (v)->{
								
							reply.put( "op_values", v );

							send( MT_PROXY_OP_REPLY, reply );
						});
																		
						break;
					}
					case PROXY_OP_REMOVE:{
						
						proxyRemoteRemove( key, (v)->{
								
							send( MT_PROXY_OP_REPLY, reply );
						});
																		
						break;
					}
					default:{
						
						throw( new Exception( "Invalid op type: " + op_type ));
					}
				}
						
				

			}else{
					
				throw( new Exception( "Invalid message type" ));
			}
		}
		
		protected void
		setProbeReplyReceived()
		{
			log( "Proxy server setup complete: iid=" + instance_id );
		
			List<byte[]> saved_keys = null;
			
			synchronized( saved_remote_key_state ){
				
				Object[] sks = saved_remote_key_state.get( source_host );
				
				if ( sks != null ){
					
					if ( SystemTime.getMonotonousTime() -  ((Long)sks[0]).longValue() < 2*60*1000 ){
						
						saved_keys = (List<byte[]>)sks[1];
					}
				}
			}	
			
			if ( saved_keys != null ){
				
				synchronized( remote_key_state ){
					
					for ( byte[] key: saved_keys){
						
						remote_key_state.put( key, "" );
					}
				}
			}
			
			setState( STATE_ACTIVE );

			Map<String,Object> reply = new HashMap<>();
				
			reply.put( "iid", instance_id );
			
			send( MT_PROXY_ALLOC_OK_REPLY, reply );
			
			proxyServerSetupComplete( this,instance_id );
		}
		
		protected void
		setProbeFailed()
		{
			log( "Probe failed to " + source_host );
			
			setState( STATE_PROBE_FAILED );
			
			Map<String,Object> reply = new HashMap<>();
									
			reply.put( "contacts", getBackupContacts());
			
			send( MT_PROXY_ALLOC_FAIL_REPLY, reply );

				// if we fail immediately the reply doesn't get sent...
			
			setDisconnectAfterSeconds( 10 );
		}
				
		private void
		proxyRemotePut(
			byte[]						key,
			byte[]						value,
			Map<String,Object>			options,
			ProxyRemoteRequestListener	listener )
		{
			ProxyRemoteRequestPut	request = new ProxyRemoteRequestPut( this, key, value, options, listener );
			
			addRemoteRequest( request );
		}
		
		private void
		proxyRemoteGet(
			byte[]						key,
			Map<String,Object>			options,
			ProxyRemoteRequestListener	listener )
		{
			ProxyRemoteRequestGet	request = new ProxyRemoteRequestGet( this, key, options, listener );
			
			addRemoteRequest( request );
		}
		
		private void
		proxyRemoteRemove(
			byte[]				key,
			ProxyRemoteRequestListener	listener )
		
			throws Exception
		{
			ProxyRemoteRequestRemove request = new ProxyRemoteRequestRemove( this, key, listener );
			
			addRemoteRequest( request );
		}
				
		private void
		addRemoteRequest(
			ProxyRemoteRequest		request )
		{
			if ( getState() != STATE_ACTIVE ){
				
				return;
			}
			
			ProxyRemoteRequest	to_exec = null;
			
			synchronized( remote_key_state ){
						
				int request_type = request.getType();
				
				int type = request_type == ProxyRemoteRequest.RT_GET?0:1;
								
				if ( 	queued_requests[0].size() + queued_requests[1].size() > MAX_QUEUED_REQUESTS || 
						( request_type == ProxyRemoteRequest.RT_PUT && remote_key_state.size() > MAX_PROXY_KEY_STATE )){
					
						// TODO: Could inform caller????
					
					request.requestComplete( new ArrayList<byte[]>());
					
					return;
				}
				
				LinkedList<ProxyRemoteRequest>	requests 	= queued_requests[type];

				requests.addFirst( request );
				
				if ( active_request_count[type] < MAX_ACTIVE_REQUESTS[type] ){
					
					active_request_count[type]++;
					
					to_exec = requests.removeLast();
				}
			}
			
			if ( to_exec != null ){
				
				to_exec.execute();
			}
		}
		
		private void
		remoteRequestComplete(
			ProxyRemoteRequest		request )
		{
			if ( getState() != STATE_ACTIVE ){
				
				return;
			}

			int type = request.getType() == ProxyRemoteRequest.RT_GET?0:1;

			ProxyRemoteRequest	to_exec = null;
			
			synchronized( remote_key_state ){
				
				LinkedList<ProxyRemoteRequest>	requests 	= queued_requests[type];

				active_request_count[type]--;
				
				if ( active_request_count[type] < MAX_ACTIVE_REQUESTS[type] && !requests.isEmpty()){
					
					active_request_count[type]++;
					
					to_exec = requests.removeLast();
				}
			}
			
			if ( to_exec != null ){
				
				to_exec.execute();
			}
		}
		
		protected void
		addKeyState(
			byte[]		key )
		{
			synchronized( remote_key_state ){
				
				remote_key_state.put( key, "" );
			}
		}
		
		protected void
		removeKeyState(
			byte[]		key )
		{
			synchronized( remote_key_state ){
			
				remote_key_state.remove( key );
			}
		}
		
		protected void
		checkRequests()
		{
			List<ProxyRemoteRequestGet>	failed = new ArrayList<>();
			
			synchronized( remote_key_state ){
				
				if ( ENABLE_LOGGING ){
					
					log( "RKS " + source_host + " size=" + remote_key_state.size() + 
							", active=" + active_request_count[0] + "/" +active_request_count[1] + 
							", queued=" + queued_requests[0].size() + "/" + queued_requests[1].size());
					
					/*
					for ( byte[]key: remote_key_state.keys()){
						
						log( "    " + ByteFormatter.encodeString(key,0,Math.min(key.length,8)));
					}
					*/
				}
				
				LinkedList<ProxyRemoteRequest>	requests = queued_requests[0];	// get queue
				
				if ( requests.isEmpty()){
					
					return;
				}
				
				long now_mono = SystemTime.getMonotonousTime();
				
				Iterator<ProxyRemoteRequest> it = requests.iterator();
				
				while( it.hasNext()){
					
					ProxyRemoteRequestGet request = (ProxyRemoteRequestGet)it.next();
									
					long elapsed = now_mono - request.getCreateTimeMono();
										
					if ( elapsed > request.getTimeout()){
						
						it.remove();
						
						failed.add( request );
					}	
				}
			}
			
			for ( ProxyRemoteRequestGet request: failed ){
				
				request.requestFailed();
			}
		}
		
		@Override
		protected void 
		setClosed()
		{
			setState( STATE_FAILED );
			
			List<byte[]> state_keys;
			
			synchronized( remote_key_state ){
				
				state_keys = new ArrayList<byte[]>( remote_key_state.keys());
				
				queued_requests[0].clear();
				queued_requests[1].clear();
			}
			
			if ( !state_keys.isEmpty()){
				
				synchronized( saved_remote_key_state ){
						
					saved_remote_key_state.put( 
							source_host, 
							new Object[]{ SystemTime.getMonotonousTime(), state_keys });
				}
			}
		}
		
		protected String
		getString()
		{
			return( super.getString() + ": " + source_host + "; state=" + getState() + "; age=" + TimeFormatter.formatColon(getAgeSeconds()));
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
			int 				type,
			Map<String,Object> 	request ) 
			
			throws Exception
		{
			if ( type == MT_PROXY_PROBE_REQUEST ){
								
				setDisconnectAfterSeconds( 60 );

				request = BDecoder.decodeStrings( request );
				
				String uid = (String)request.get( "uid" );
				
				source_host = (String)request.get( "source_host" );
				
				OutboundConnectionProxy cp = current_client_proxy;
				
				if ( cp != null && cp.getLocalUID().equals( uid )){
				
					Map<String,Object> reply = new HashMap<>();
				
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
	ActiveRequestListener
	{
		public void
		complete(
			OutboundConnectionProxy		proxy,
			ProxyLocalRequest				request );
		
		public void
		failed(
			OutboundConnectionProxy		proxy,
			ProxyLocalRequest				request );
	}
	
	private abstract class
	ProxyLocalRequest
	{
		public static final int RT_PUT			= 1;
		public static final int RT_GET			= 2;
		public static final int RT_REMOVE		= 3;
	
		private final long		start_mono = SystemTime.getMonotonousTime();
		
		private final long					seq	= proxy_request_seq.incrementAndGet();
		private final byte[]				key;
		private	final ProxyRequestListener	listener;

		private Map<String,Object>			options;
		
		private AtomicBoolean done = new AtomicBoolean();

		protected
		ProxyLocalRequest(
			byte[]					_key,
			ProxyRequestListener	_listener )
		{
			key			= _key;
			listener	= _listener;
		}
		
		protected abstract String
		getString();
		
		protected long
		getSequence()
		{
			return( seq );
		}
		
		protected long
		getStartTimeMono()
		{
			return( start_mono );
		}
		
		protected abstract int
		getType();
		
		protected byte[]
		getKey()
		{
			return( key );
		}
		
		protected ProxyRequestListener
		getListener()
		{
			return( listener );
		}
		
		protected Map<String,Object>
		getOptions()
		{
			return( options );
		}
		
		protected void
		setOptions(
			Map<String,Object>		_options )
		{
			options	= _options;
		}
		
		protected void
		setFailed()
		{
			if ( !done.compareAndSet( false, true )){
				
				return;
			}
			
			if ( listener != null ){
				
				try{
					listener.complete( getKey(), true );
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
		}
		
		protected void
		setComplete()
		{
			if ( !done.compareAndSet( false, true )){
				
				return;
			}
			
			if ( listener != null ){
			
				try{
					listener.complete( getKey(), false );
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
		}
	}
	
	private class
	ProxyLocalRequestPut
		extends ProxyLocalRequest
	{
		private final Map<String,Object>	value;
		
		protected
		ProxyLocalRequestPut(
			byte[]					_key,
			Map<String,Object>		_value,
			ProxyRequestListener	_listener )
		{
			super( _key, _listener );
			
			value = _value;
		}
				
		protected String
		getString()
		{
			return( "put/" + getSequence());
		}

		protected int
		getType()
		{
			return( RT_PUT );
		}
		
		protected Map<String,Object>
		getValue()
		{
			return( value );
		}
	}
	
	private class
	ProxyLocalRequestGet
		extends ProxyLocalRequest
	{
		private long				timeout = 2*60*1000;
				
		protected
		ProxyLocalRequestGet(
			byte[]					key,
			ProxyRequestListener	listener )
		{
			super( key, listener );
		}
		
		protected String
		getString()
		{
			return( "get/" + getSequence());
		}
		
		protected int
		getType()
		{
			return( RT_GET );
		}
		
		protected void
		setTimeout(
			long	t )
		{
			timeout = t;
		}
		
		protected long
		getTimeout()
		{
			return( timeout );
		}
		
		protected void
		setValues(
			List<Map<String,Object>> values )
		{		
			ProxyRequestListener	listener = getListener();
			
			if ( listener != null ){
				
				try{
					listener.valuesRead( getKey(), values );
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
		}
	}
	
	private class
	ProxyLocalRequestRemove
		extends ProxyLocalRequest
	{
		protected
		ProxyLocalRequestRemove(
			byte[]					key,
			ProxyRequestListener	listener )
		{
			super( key, listener );
		}
		
		protected String
		getString()
		{
			return( "del/" + getSequence());
		}
		
		protected int
		getType()
		{
			return( RT_REMOVE );
		}
	}
	
	public interface
	ProxyRequestListener
	{
		public void
		valuesRead(
			byte[]							key,
			List<Map<String,Object>>		value );
		
		public void
		complete(
			byte[]				key,
			boolean				timeout );
	}
	
	public abstract class
	ProxyRequestAdapter
		implements ProxyRequestListener
	{
		public void
		valuesRead(
			byte[]							key,
			List<Map<String,Object>>		value )
		{
		}
	}
	
	public interface
	TorProxyDHTListener
	{
		public void
		proxyValueRead(
			InetSocketAddress	originator,
			boolean				is_seed,
			boolean				crypto_required );
		
		public void
		proxyComplete(
			byte[]		key,
			boolean		timeout );
	}
	
	public abstract static class
	TorProxyDHTAdapter
		implements TorProxyDHTListener
	{
		public void
		proxyValueRead(
			InetSocketAddress	originator,
			boolean				is_seed,
			boolean				crypto_required )
		{
		}
	}
}
