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

package org.parg.azureus.plugins.networks.i2p.vuzedht;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import org.parg.azureus.plugins.networks.i2p.I2PHelperAdapter;
import org.parg.azureus.plugins.networks.i2p.router.I2PHelperRouter;

import com.biglybt.core.dht.DHT;
import com.biglybt.core.dht.DHTFactory;
import com.biglybt.core.dht.DHTLogger;
import com.biglybt.core.dht.netcoords.DHTNetworkPosition;
import com.biglybt.core.dht.transport.DHTTransport;
import com.biglybt.core.dht.transport.DHTTransportAlternativeContact;
import com.biglybt.core.dht.transport.DHTTransportAlternativeNetwork;
import com.biglybt.core.dht.transport.DHTTransportContact;
import com.biglybt.core.dht.transport.DHTTransportException;
import com.biglybt.core.dht.transport.DHTTransportFullStats;
import com.biglybt.core.dht.transport.DHTTransportListener;
import com.biglybt.core.dht.transport.DHTTransportProgressListener;
import com.biglybt.core.dht.transport.DHTTransportReplyHandler;
import com.biglybt.core.dht.transport.DHTTransportReplyHandlerAdapter;
import com.biglybt.core.dht.transport.DHTTransportRequestHandler;
import com.biglybt.core.dht.transport.DHTTransportStats;
import com.biglybt.core.dht.transport.DHTTransportTransferHandler;
import com.biglybt.core.dht.transport.DHTTransportValue;
import com.biglybt.core.dht.transport.udp.impl.DHTUDPUtils;
import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEventPeriodic;
import com.biglybt.pif.PluginInterface;
import com.biglybt.plugin.dht.impl.DHTPluginStorageManager;
import com.biglybt.util.MapUtils;

public class 
DHTBG
	implements DHTLogger
{
	private final I2PHelperAdapter			adapter;
	
	private final boolean					enable_alt_contacts;

	private TimerEventPeriodic				timer;
	
	private DHTTransportAlternativeNetworkImpl		ipv4_net;
	private DHTTransportAlternativeNetworkImpl		ipv6_net;

	private volatile DHTTransportContactBG	most_recent_alive;
	
	private final DHTTransportAZ		az_transport;
	private final DHTTransportBG		bg_transport;
	
	private DHT							dht;
	private DHTPluginStorageManager 	storage_manager;

	
	public
	DHTBG(
		DHTI2P				_i2p_dht,
		DHTTransportAZ		_az_transport,
		I2PHelperAdapter	_adapter )
	{		
		az_transport	= _az_transport;
		adapter			= _adapter;
		
		int dht_index  = _i2p_dht.getDHTIndex();
		
		File storage_dir = new File( _i2p_dht.getDir(), "dhtdata_bg" + (dht_index==0?"":String.valueOf(dht_index)));
		
		if ( !storage_dir.isDirectory()){
		
			storage_dir.mkdirs();
		}

		storage_manager = new DHTPluginStorageManager( DHTUtilsI2P.DHT_NETWORK, this, storage_dir );
		
		Properties	props = new Properties();
	
		props.put( DHT.PR_ENABLE_RANDOM_LOOKUP, 	0 );		// don't need random DHT poking as we use the basis for this

		props.put( DHT.PR_CONTACTS_PER_NODE, 			10 );		// K
		props.put( DHT.PR_NODE_SPLIT_FACTOR, 			2 );		// B

		bg_transport = new DHTTransportBG();
		
		dht = DHTFactory.create( 
					bg_transport, 
					props,
					storage_manager,
					null,
					this );
		
		storage_manager.importContacts( dht );
		
		enable_alt_contacts	= dht_index == I2PHelperRouter.DHT_MIX;
		
		if ( enable_alt_contacts ){
			
			ipv4_net = new DHTTransportAlternativeNetworkImpl( 4 ); // FIX ME POST 2203 DHTTransportAlternativeNetwork.AT_BIGLYBT_IPV4 );
			ipv6_net = new DHTTransportAlternativeNetworkImpl( 5 ); // FIX ME POST 2203 DHTTransportAlternativeNetwork.AT_BIGLYBT_IPV6 );

			DHTUDPUtils.registerAlternativeNetwork( ipv4_net );
			DHTUDPUtils.registerAlternativeNetwork( ipv6_net );
			
			timer = SimpleTimer.addPeriodicEvent( "BG:AC", 30*1000, (ev)->{
				
				DHTTransportContactBG contact = most_recent_alive;
				
				if ( contact != null ){
					
					contact.sendPingForce(
						new DHTTransportReplyHandlerAdapter(){
							
							@Override
							public void 
							pingReply(
								DHTTransportContact contact, 
								int elapsed_time)
							{
							}

							@Override
							public void 
							failed(
								DHTTransportContact contact, 
								Throwable error)
							{
							}
						});
				}
			});		
		}
	}
	
	protected void
	setSeeded()
	{
		dht.getControl().setSeeded();
	}
	
	public void
	sendPingPreprocess(
		DHTTransportContactAZ		contact,
		Map<String,Object>			payload )
	{
		if ( enable_alt_contacts && ( contact.getProtocolVersion() >= DHTUtilsI2P.PROTOCOL_VERSION_ALT_CONTACTS )){

			Map<String,Long>	ac = new HashMap<>();
							
			long req4 = ipv4_net.getRequiredContactCount();
			
			if ( req4 > 0 ){
			
				ac.put( String.valueOf( ipv4_net.getNetworkType()), req4 );
			}
			
			long req6 = ipv6_net.getRequiredContactCount();
			
			if ( req6 > 0 ){
			
				ac.put( String.valueOf( ipv6_net.getNetworkType()), req6 );
			}

			if ( !ac.isEmpty()){
			
				payload.put( "ac", ac );
			}
		}
	}
	
	public void
	sendPingPostprocess(
		DHTTransportContactAZ		contact,
		Map<String,Object>			payload )
	{
		if ( enable_alt_contacts && ( contact.getProtocolVersion() >= DHTUtilsI2P.PROTOCOL_VERSION_ALT_CONTACTS )){

			Map<String,List<Map<String,Object>>> ac = (Map<String,List<Map<String,Object>>>)payload.get( "ac" );
			
			if ( ac != null ){
								
				for ( Map.Entry<String,List<Map<String,Object>>> entry: ac.entrySet()){
					
					long net = Long.parseLong( entry.getKey());
					
					List<Map<String,Object>> contacts = entry.getValue();
					
					if ( net == ipv4_net.getNetworkType()){
						
						ipv4_net.addContactsFromReply( contacts );
						
					}else if ( net == ipv6_net.getNetworkType()){
						
						ipv6_net.addContactsFromReply( contacts );
					}
				}
			}
		}
	}
	
	public void
	receivePingPostprocess(
		DHTTransportContactAZ		contact,
		Map<String,Object>			payload )
	{
		if ( enable_alt_contacts && ( contact.getProtocolVersion() >= DHTUtilsI2P.PROTOCOL_VERSION_ALT_CONTACTS )){
			
			Map<String,Long> ac_in = (Map<String,Long>)payload.get( "ac" );
			
			if ( ac_in != null ){
				
				Map<String,List>	ac_out = new HashMap<>();
								
				for ( Map.Entry<String,Long> entry: ac_in.entrySet()){
					
					long 	net 	= Long.parseLong( entry.getKey());
					int 	wanted 	= entry.getValue().intValue();
					
					List contacts;
					
					if ( net == ipv4_net.getNetworkType()){
						
						contacts = ipv4_net.getContactsForReply( wanted );
						
					}else if ( net == ipv6_net.getNetworkType()){
						
						contacts = ipv6_net.getContactsForReply( wanted );
						
					}else{
						
						contacts = null;
					}
					
					if ( contacts != null && !contacts.isEmpty()){
						
						ac_out.put( entry.getKey(), contacts );
					}
				}
				
				if ( !ac_out.isEmpty()){
					
					payload.put( "ac", ac_out );
				}
			}
		}
	}
	
	public void
	contactAlive(
		DHTTransportContactAZ		contact )
	{
		if ( contact.getProtocolVersion() >= DHTUtilsI2P.PROTOCOL_VERSION_ALT_CONTACTS ){
		
			DHTTransportContactBG bgc = new DHTTransportContactBG( bg_transport, contact );
			
			if ( enable_alt_contacts ){
			
				most_recent_alive = bgc;
			}
			
			bg_transport.addContact( bgc );
		}
	}
	
	public void
	contactDead(
		DHTTransportContactAZ		contact )
	{
		if ( contact.getProtocolVersion() >= DHTUtilsI2P.PROTOCOL_VERSION_ALT_CONTACTS ){
		
			DHTTransportContactBG bgc = new DHTTransportContactBG( bg_transport, contact );
			
			bg_transport.removeContact( bgc );
		}
	}
	
	public long
	getEstimatedDHTSize()
	{
		return( dht.getControl().getStats().getEstimatedDHTSize());
	}
	
	public void
	destroy()
	{
		if ( enable_alt_contacts ){
			
			DHTUDPUtils.unregisterAlternativeNetwork( ipv4_net );
			DHTUDPUtils.unregisterAlternativeNetwork( ipv6_net );
			
			timer.cancel();
		}
		
		storage_manager.exportContacts( dht );
	}
	
	
	@Override
	public void
	log(
		String	str )
	{
		adapter.log( str );
	}
		
	@Override
	public void
	log(
		Throwable	e )
	{
		adapter.log( Debug.getNestedExceptionMessage(e));
	}
	
	@Override
	public void
	log(
		int		log_type,
		String	str )
	{
		adapter.log( str );
	}
	
	@Override
	public boolean
	isEnabled(
		int	log_type )
	{
		return( true );
	}
			
	@Override
	public PluginInterface
	getPluginInterface()
	{
		// this currently prevents the speed-tester from being created (which is what we want) so
		// if this gets changed that will need to be prevented by another means
		return( null );
	}
	
	private static class
	DHTTransportAlternativeNetworkImpl
		implements DHTTransportAlternativeNetwork
	{
		private static final int max_contacts	= 32;
		
		private int	network;
		
		private final TreeSet<DHTTransportAlternativeContact> contacts =
			new TreeSet<>(
					new Comparator<DHTTransportAlternativeContact>() {
						@Override
						public int
						compare(
							DHTTransportAlternativeContact o1,
							DHTTransportAlternativeContact o2) 
						{
							int res = o1.getAge() - o2.getAge();

							if (res == 0) {

								res = o1.getID() - o2.getID();
							}

							return (res);
						}
					});
		
		private boolean contacts_disable;
		
		private
		DHTTransportAlternativeNetworkImpl(
			int			net )
		{
			network	= net;
		}
		
		@Override
		public int
		getNetworkType()
		{
			return( network );
		}
		
		private long
		getRequiredContactCount()
		{
			int num;
			
			synchronized( contacts ){
			
				num = contacts.size();
			}
			
			return( num<8?3:1 );	// keep it ticking over
		}
		
		private void
		addContactsFromReply(
			List<Map<String,Object>>		reply )
		{
			synchronized( contacts ){
			
				for ( Map m: reply ){
					
					try{
						int version = MapUtils.getMapInt( m, "v", 1 );
						int age		= MapUtils.getMapInt( m, "a", 0 );
						
						Map<String,Object>	properties = (Map<String,Object>)m.get( "p" );
						
						contacts.add( new DHTTransportAlternativeContactImpl( version, age, properties ));
						
					}catch( Throwable e ){
						
					}
				}
				
				if ( contacts.size() > max_contacts + 16 ){	// don't trim every time once full ....
					
					int	pos = 0;
					
					Iterator<DHTTransportAlternativeContact> it = contacts.iterator();

					while( it.hasNext()){
		
						it.next();
		
						pos++;
		
						if(  pos > max_contacts ){
		
							it.remove();
						}
					}
				}
			}
		}
		
		private List<Map<String,Object>>
		getContactsForReply(
			int			wanted )
		{
			List<Map<String,Object>>	result = new ArrayList<>( wanted );
			
			List<DHTTransportAlternativeContact> to_return;

			synchronized( contacts ){
								
				try{
					contacts_disable = true;	// force use of the core provider
					
					to_return = DHTUDPUtils.getAlternativeContacts( network, wanted );
					
				}finally{
					
					contacts_disable = false;
				}
				
				if ( to_return.size() < wanted ){
				
					for ( DHTTransportAlternativeContact c: contacts ){
						
						to_return.add( c );
						
						if ( to_return.size() == wanted ){
							
							break;
						}
					}
				}
			}
			
			for ( DHTTransportAlternativeContact c: to_return ){
				
				Map<String,Object> m = new HashMap<>();
				
				result.add( m );
				
				// m.put( "n", c.getNetworkType()); not needed
				m.put( "v", c.getVersion());
				m.put( "a", c.getAge());
				m.put( "p", c.getProperties());
			}
			
			return( result );
		}
		
		@Override
		public List<DHTTransportAlternativeContact>
		getContacts(
			int		max )
		{
			List<DHTTransportAlternativeContact> result = new ArrayList<DHTTransportAlternativeContact>( max );
			
			synchronized( contacts ){
				
				if ( !contacts_disable ){
					
					for ( DHTTransportAlternativeContact c: contacts ){
						
						result.add( c );
						
						if ( result.size() == max ){
							
							break;
						}
					}
				}
			}
			
			return( result );
		}
		
		private class
		DHTTransportAlternativeContactImpl
			implements DHTTransportAlternativeContact
		{
			private final int					version;
			private final Map<String,Object>	properties;
			private final int					start_secs;
			private final long	 				start_time;
			private final int	 				id;
			
			private
			DHTTransportAlternativeContactImpl(
				int						_version,
				int						_age_secs,
				Map<String,Object>		_properties )
			{
				version		= _version;
				properties	= _properties;
				
				start_secs	= _age_secs;
				start_time 	= SystemTime.getMonotonousTime();
				
				int	_id;
				
				try{
				
					_id = Arrays.hashCode( BEncoder.encode( properties ));
					
				}catch( Throwable e ){
					
					Debug.out( e );
					
					_id = 0;
				}
				
				id	= _id;
			}
			
			@Override
			public int
			getNetworkType()
			{
				return( network );
			}
			
			@Override
			public int
			getVersion()
			{
				return( version );
			}
			
			@Override
			public int
			getID()
			{
				return( id );
			}
			
			@Override
			public int
			getLastAlive()
			{
				return( 0 );	// deprecated
			}
			
			@Override
			public int
			getAge()
			{
				return(start_secs + (int)((SystemTime.getMonotonousTime() - start_time )/1000 ));
			}
			
			@Override
			public Map<String,Object>
			getProperties()
			{
				return( properties );
			}
		}
	}
	
	private class
	DHTTransportBG
		implements DHTTransport
	{
		private DHTTransportRequestHandler	request_handler;
		
		private DHTTransportContactBG local_contact = new DHTTransportContactBG( this, (DHTTransportContactAZ)az_transport.getLocalContact());
		
		public byte
		getProtocolVersion()
		{
			return( az_transport.getProtocolVersion());
		}

		public byte
		getMinimumProtocolVersion()
		{
			return( az_transport.getMinimumProtocolVersion());
		}

		public int
		getNetwork()
		{
			return( az_transport.getNetwork());
		}

		public boolean
		isIPV6()
		{
			return( false );
		}

		public byte
		getGenericFlags()
		{
			return( az_transport.getGenericFlags());
		}

		public void
		setGenericFlag(
			byte		flag,
			boolean		value )
		{
		}
		

		public void
		setSuspended(
			boolean			susp )
		{
		}

		public DHTTransportContact
		getLocalContact()
		{
			return( local_contact );
		}

		public int
		getPort()
		{
			return( az_transport.getPort());
		}

		public void
		setPort(
			int	port )

			throws DHTTransportException
		{
			az_transport.setPort(port);
		}
		
		public long
		getTimeout()
		{
			return( az_transport.getTimeout());
		}

		public void
		setTimeout(
			long		millis )
		{
			az_transport.setTimeout(millis);
		}

		public DHTTransportContact
		importContact(
			DataInputStream		is,
			boolean				is_bootstrap )

			throws IOException, DHTTransportException
		{
			DHTTransportContact contact = az_transport.importContact( is, is_bootstrap );
			
			if ( contact.getProtocolVersion() >= DHTUtilsI2P.PROTOCOL_VERSION_ALT_CONTACTS ){
				
				return( new DHTTransportContactBG( this, (DHTTransportContactAZ)contact ));
			}else{
				
				throw( new DHTTransportException( "Invalid contact version" ));
			}
		}

			/**
			 * Set the handler for incoming requests
			 * @param receiver
			 */

		public void
		setRequestHandler(
			DHTTransportRequestHandler	receiver )
		{
			request_handler = receiver;
		}

		protected void
		addContact(
			DHTTransportContactBG c )
		{
			if ( request_handler != null ){
				
				request_handler.contactImported( c,  false );
			}
		}
		
		protected void
		removeContact(
			DHTTransportContactBG c )
		{
			if ( request_handler != null ){
				
				request_handler.contactRemoved( c );
			}
		}
		
		public DHTTransportStats
		getStats()
		{
			return( az_transport.getStats());
		}

			// direct contact-contact communication

		public void
		registerTransferHandler(
			byte[]							handler_key,
			DHTTransportTransferHandler		handler )
		{
		}

		public void
		registerTransferHandler(
			byte[]							handler_key,
			DHTTransportTransferHandler		handler,
			Map<String,Object>				options )
		{
		}

		public void
		unregisterTransferHandler(
			byte[]						handler_key,
			DHTTransportTransferHandler	handler )
		{
		}

		public byte[]
		readTransfer(
			DHTTransportProgressListener	listener,
			DHTTransportContact				target,
			byte[]							handler_key,
			byte[]							key,
			long							timeout )

			throws DHTTransportException
		{	
			return( null );
		}

		public void
		writeTransfer(
			DHTTransportProgressListener	listener,
			DHTTransportContact				target,
			byte[]							handler_key,
			byte[]							key,
			byte[]							data,
			long							timeout )

			throws DHTTransportException
		{	
		}

		public byte[]
		writeReadTransfer(
			DHTTransportProgressListener	listener,
			DHTTransportContact				target,
			byte[]							handler_key,
			byte[]							data,
			long							timeout )

			throws DHTTransportException
		{
			return( null );
		}

		public boolean
		supportsStorage()
		{
			return( true );
		}

		public boolean
		isReachable()
		{
			return( true );
		}

		public DHTTransportContact[]
		getReachableContacts()
		{
			return( az_transport.getReachableContacts());
		}

		public DHTTransportContact[]
		getRecentContacts()
		{
			return( az_transport.getRecentContacts());
		}


		public void
		addListener(
			DHTTransportListener	l )
		{
		}

		public void
		removeListener(
			DHTTransportListener	l )
		{
		}
	}
	
	public class 
	DHTTransportContactBG
		implements DHTTransportContact
	{
		private DHTTransportBG			transport;
		private DHTTransportContactAZ	basis;
			
		protected
		DHTTransportContactBG(
			DHTTransportBG			_transport,
			DHTTransportContactAZ	_basis )
		{
			transport		= _transport;
			basis			= _basis;
		}
		
		protected DHTTransportContactAZ
		getBasis()
		{
			return( basis );
		}
		
		@Override
		public int
		getMaxFailForLiveCount()
		{
			return( 3 );
		}
		
		@Override
		public int
		getMaxFailForUnknownCount()
		{
			return( 2 );
		}
		
		@Override
		public int
		getInstanceID()
		{
			return( basis.getInstanceID());
		}
		
		@Override
		public byte[]
		getID()
		{
			return( basis.getID());
		}
		
		@Override
		public byte
		getProtocolVersion()
		{
				// TODO: There is some interaction with the protocol version and the DHTControlImpl etc
			
			return( basis.getProtocolVersion());
		}
		
		@Override
		public long
		getClockSkew()
		{
			return( basis.getClockSkew());
		}
		
		@Override
		public int
		getRandomIDType()
		{
			return( RANDOM_ID_TYPE2 );
		}
		
		@Override
		public void
		setRandomID(
			int	id )
		{
			System.out.println( "nuhuh" );
		}
		
		@Override
		public int
		getRandomID()
		{
			System.out.println( "nuhuh" );
			
			return(0);
		}
		
		@Override
		public void
		setRandomID2(
			byte[]		id )
		{
			basis.setRandomID2( id );
		}
		
		protected long
		getRandomID2Age()
		{
			return( basis.getRandomID2Age());
		}
		
		@Override
		public byte[]
		getRandomID2()
		{
			return( basis.getRandomID2());
		}
		
		@Override
		public String
		getName()
		{
			return( basis.getName());
		}
		
		@Override
		public byte[]
		getBloomKey()
		{
			return( basis.getBloomKey());
		}
		
		@Override
		public InetSocketAddress
		getAddress()
		{
			return( basis.getAddress());
		}
		
		@Override
		public InetSocketAddress
		getTransportAddress()
		{
			return( getAddress());
		}
		
		@Override
		public InetSocketAddress
		getExternalAddress()
		{
			return( getAddress());
		}
		
		@Override
		public boolean
		isAlive(
			long		timeout )
		{
			System.out.println( "isAlive" );
			
			return( true );	// derp
		}

		@Override
		public void
		isAlive(
			DHTTransportReplyHandler	handler,
			long						timeout )
		{
			System.out.println( "isAlive2" );
		}
		
		@Override
		public boolean
		isValid()
		{
			return( true );
		}
		
		@Override
		public boolean
		isSleeping()
		{
			return( basis.isSleeping());
		}
		
		@Override
		public void
		sendPing(
			DHTTransportReplyHandler	handler )
		{
			basis.sendPing( new DHTTransportReplyHandlerBG( this, handler ));
		}
		
		public void
		sendPingForce(
			DHTTransportReplyHandler	handler )
		{
			basis.sendPingForce(new DHTTransportReplyHandlerBG( this, handler ));
		}
		
		@Override
		public void
		sendImmediatePing(
			DHTTransportReplyHandler	handler,
			long						timeout )
		{
			basis.sendImmediatePing(new DHTTransportReplyHandlerBG( this, handler ), timeout);
		}

		@Override
		public void
		sendStats(
			DHTTransportReplyHandler	handler )
		{
			basis.sendStats(new DHTTransportReplyHandlerBG( this, handler ));	
		}
		
		@Override
		public void
		sendStore(
			DHTTransportReplyHandler	handler,
			byte[][]					keys,
			DHTTransportValue[][]		value_sets,
			boolean						immediate )
		{
			basis.sendStore(new DHTTransportReplyHandlerBG( this, handler ), keys, value_sets, immediate);
		}
		
		@Override
		public void
		sendQueryStore(
			DHTTransportReplyHandler	handler,
			int							header_length,
			List<Object[]>				key_details )
		{
			basis.sendQueryStore(new DHTTransportReplyHandlerBG( this, handler ), header_length, key_details);
		}
		
		@Override
		public void
		sendFindNode(
			DHTTransportReplyHandler	handler,
			byte[]						id,
			short						flags )
		{		
			basis.sendFindNode(new DHTTransportReplyHandlerBG( this, handler ), id, flags);
		}
			
		@Override
		public void
		sendFindValue(
			DHTTransportReplyHandler	handler,
			byte[]						key,
			int							max_values,
			short						flags )
		{
			basis.sendFindValue(new DHTTransportReplyHandlerBG( this, handler ), key, max_values, flags);
		}
			
		@Override
		public void
		sendKeyBlock(
			DHTTransportReplyHandler	handler,
			byte[]						key_block_request,
			byte[]						key_block_signature )
		{
			basis.sendKeyBlock(new DHTTransportReplyHandlerBG( this, handler ), key_block_request, key_block_signature);
		}

		@Override
		public DHTTransportFullStats
		getStats()
		{
			return( basis.getStats());
		}
		
		@Override
		public void
		exportContact(
			DataOutputStream	os )
		
			throws IOException, DHTTransportException
		{
			basis.exportContact( os );
		}
		
		@Override
		public Map<String, Object>
		exportContactToMap()
		{
			return( basis.exportContactToMap());
		}
		
		@Override
		public void
		remove()
		{
			basis.remove();
		}
		
		@Override
		public void
		createNetworkPositions(
			boolean		is_local )
		{
		}
				
		@Override
		public DHTNetworkPosition[]
		getNetworkPositions()
		{
			return( new DHTNetworkPosition[0]);
		}
		
		@Override
		public DHTNetworkPosition
		getNetworkPosition(
			byte	position_type )
		{
			return( null );
		}

		@Override
		public DHTTransport
		getTransport()
		{
			return( transport );
		}
		
		@Override
		public String
		getString()
		{
			return( "BG:" + basis.getString());
		}
	}
	
	private class
	DHTTransportReplyHandlerBG 
		implements DHTTransportReplyHandler
	{
		final DHTTransportContactBG		bg_contact;
		final DHTTransportReplyHandler	delegate;
		
		private
		DHTTransportReplyHandlerBG(
			DHTTransportContactBG		_bgc,
			DHTTransportReplyHandler	_delegate )
		{
			bg_contact	= _bgc;
			delegate 	= _delegate;
		}
		
		public void
		pingReply(
			DHTTransportContact contact,
			int					elapsed_time )
		{
			delegate.pingReply( bg_contact, elapsed_time );
		}

		public void
		statsReply(
			DHTTransportContact 	contact,
			DHTTransportFullStats	stats )
		{
			delegate.statsReply( bg_contact, stats );
		}

		public void
		storeReply(
			DHTTransportContact contact,
			byte[]				diversifications )
		{
			delegate.storeReply( bg_contact, diversifications);
		}

		public void
		queryStoreReply(
			DHTTransportContact contact,
			List<byte[]>		response )
		{
			delegate.queryStoreReply( bg_contact, response);
		}

		public void
		findNodeReply(
			DHTTransportContact 	contact,
			DHTTransportContact[]	contacts )
		{
			delegate.findNodeReply( bg_contact, filter( contacts ));
		}

		public void
		findValueReply(
			DHTTransportContact 	contact,
			DHTTransportValue[]		values,
			byte					diversification_type,
			boolean					more_to_come )
		{
			delegate.findValueReply( bg_contact, values, diversification_type, more_to_come );
		}

		public void
		findValueReply(
			DHTTransportContact 	contact,
			DHTTransportContact[]	contacts )
		{
			delegate.findValueReply( bg_contact, filter( contacts ));
		}

		public void
		keyBlockReply(
			DHTTransportContact 	contact )
		{
			delegate.keyBlockReply( bg_contact );
		}

		public void
		keyBlockRequest(
			DHTTransportContact 	contact,
			byte[]					key,
			byte[]					key_signature )
		{
			delegate.keyBlockRequest( bg_contact, key, key_signature);
		}

		public void
		failed(
			DHTTransportContact 	contact,
			Throwable				error )
		{
			delegate.failed( bg_contact, error);
		}

		private DHTTransportContact[]
		filter(
			DHTTransportContact[]		contacts )
		{
			List<DHTTransportContact> result = new ArrayList<>();
			
			for ( DHTTransportContact contact: contacts ){
				
				if ( contact.getProtocolVersion() >= DHTUtilsI2P.PROTOCOL_VERSION_ALT_CONTACTS ){
					
					result.add( new DHTTransportContactBG( bg_transport, (DHTTransportContactAZ)contact ));
				}
			}
			
			return( result.toArray(new DHTTransportContact[result.size()]));
		}
	};
}
