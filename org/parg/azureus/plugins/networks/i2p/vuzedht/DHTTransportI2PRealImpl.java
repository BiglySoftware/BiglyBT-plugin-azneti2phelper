/*
 * Created on Apr 16, 2014
 * Created by Paul Gardner
 * 
 * Copyright 2014 Azureus Software, Inc.  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */



package org.parg.azureus.plugins.networks.i2p.vuzedht;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.biglybt.core.util.AERunnable;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.Average;
import com.biglybt.core.util.BDecoder;
import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.ByteFormatter;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.HashWrapper;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.ThreadPool;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.core.util.TimerEventPeriodic;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NID;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NodeInfo;

import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.SendMessageOptions;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.client.datagram.I2PDatagramMaker;
import net.i2p.client.datagram.I2PInvalidDatagramException;
import net.i2p.data.Base32;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

import com.biglybt.core.dht.DHT;
import com.biglybt.core.dht.impl.DHTLog;
import com.biglybt.core.dht.transport.DHTTransportContact;
import com.biglybt.core.dht.transport.DHTTransportException;
import com.biglybt.core.dht.transport.DHTTransportFindValueReply;
import com.biglybt.core.dht.transport.DHTTransportFullStats;
import com.biglybt.core.dht.transport.DHTTransportListener;
import com.biglybt.core.dht.transport.DHTTransportProgressListener;
import com.biglybt.core.dht.transport.DHTTransportReplyHandler;
import com.biglybt.core.dht.transport.DHTTransportReplyHandlerAdapter;
import com.biglybt.core.dht.transport.DHTTransportRequestHandler;
import com.biglybt.core.dht.transport.DHTTransportStats;
import com.biglybt.core.dht.transport.DHTTransportTransferHandler;
import com.biglybt.core.dht.transport.DHTTransportValue;
import com.biglybt.core.dht.transport.udp.DHTTransportUDP;
import com.biglybt.core.dht.transport.util.DHTTransportRequestCounter;
import com.biglybt.core.dht.transport.util.DHTTransportStatsImpl;
import com.biglybt.util.MapUtils;

public class 
DHTTransportI2PRealImpl
	implements DHTTransportI2P, I2PSessionMuxedListener
{	
	private static final Set<String>	trace_addresses;
	
	static{
		Set<String>		addresses = null;
		
		try{
			String str = System.getProperty( "az.i2phelper.trace.addresses", "" );
			
			if ( str.length() > 0 ){
				
				addresses = new HashSet<String>();
				
				String[] bits = str.split(",");
				
				for ( String s: bits ){
					
					s = s.trim();
					
					if ( !s.endsWith( ".b32.i2p" )){
						
						s += ".b32.i2p";
					}
					
					addresses.add( s );
				}
				
				System.err.println( "Tracing addresses: " + addresses );
			}
		}catch( Throwable e ){
			
			e.printStackTrace();
		}
		
		trace_addresses = addresses;
	}

	private boolean TRACE = false;

	
	private static final int NUM_WANT	= 16;
	
	private static final int RPC_TYPE_TWO_WAY			= 1;
	private static final int RPC_TYPE_ONE_WAY			= 2;
	private static final int RPC_TYPE_UNREPLIABLE		= 3;

	private final DHTI2PAdapter			adapter;
	private final I2PSession			session;
	private final NodeInfo				my_node;
	private final int					query_port;
	private final int					reply_port;
	private final NID					my_nid;
	
	private final DHTTransportStatsI2P		stats;
	
	private final DHTTransportContactI2P		local_contact;
	
	private DHTTransportRequestHandler	request_handler;
	private AZRequestHandler			az_request_handler;
	
	private TimerEventPeriodic 			timer_event;
	
	private long						request_timeout;
	
	private byte						generic_flags	= DHTTransportUDP.GF_NONE;

	private static final int TOKEN_MAP_MAX = 512;
	
	private Map<HashWrapper,NodeInfo>	token_map =
			new LinkedHashMap<HashWrapper,NodeInfo>(TOKEN_MAP_MAX,0.75f,true)
			{
				@Override
				protected boolean
				removeEldestEntry(
			   		Map.Entry<HashWrapper,NodeInfo> eldest) 
				{
					return( size() > TOKEN_MAP_MAX );
				}
			};
	
	private ThreadPool	destination_lookup_pool_lp 	= new ThreadPool("DHTTransportI2P::destlookup-lp", 5, true );
	private ThreadPool	destination_lookup_pool_hp 	= new ThreadPool("DHTTransportI2P::destlookup-hp", 10, true );

	private static final boolean TRACE_DEST_LOOKUPS = false;
	
	private static Average		dest_lookup_rate = Average.getInstance( 1000, 10 );
	private static long			dest_lookup_count;
	private static long			dest_lookup_count_start;
	private static long			dest_lookup_count_log;
	
	private static Map<Long,String[]>	dest_lookup_tracker	= new HashMap<Long, String[]>();
	private static long					dest_lookup_consec_fail_count;
	private static long					dest_lookup_consec_fail_60_count;
	
	private static final int DEST_LOOKUP_NEGATIVE_CACHE_MAX = 8000;
	
	private Map<Long,DestLookupNegativeCacheEntry>	dest_lookup_negative_cache =
			new LinkedHashMap<Long,DestLookupNegativeCacheEntry>(DEST_LOOKUP_NEGATIVE_CACHE_MAX,0.75f,true)
			{
				@Override
				protected boolean
				removeEldestEntry(
			   		Map.Entry<Long,DestLookupNegativeCacheEntry> eldest) 
				{
					return( size() > DEST_LOOKUP_NEGATIVE_CACHE_MAX );
				}
			};
	
	
	
	
	private volatile boolean			destroyed;
	
	protected
	DHTTransportI2PRealImpl(
		DHTI2PAdapter	_dht_adapter,
		I2PSession 		_session,
		NodeInfo		_my_node,
		int				_request_timeout )
	{
		adapter			= _dht_adapter;
		session 		= _session;
		my_node			= _my_node;
		request_timeout	= _request_timeout;
		
		query_port	= my_node.getPort();
		reply_port	= query_port+1;
		my_nid		= my_node.getNID();
		
		stats = new DHTTransportStatsI2P();
				
		local_contact = 
			new DHTTransportContactI2P( 
				this, 
				my_node, 
				DHTUtilsI2P.PROTOCOL_VERSION,
				RandomUtils.nextAbsoluteInt(), 0, (byte)0 );
		
        session.addMuxedSessionListener( this, I2PSession.PROTO_DATAGRAM_RAW, reply_port );
        session.addMuxedSessionListener( this, I2PSession.PROTO_DATAGRAM, query_port );
        
        timer_event = 
				SimpleTimer.addPeriodicEvent(
					"DHTTransportI2P:timeouts",
					5000,
					new TimerEventPerformer()
					{
						@Override
						public void
						perform(
							TimerEvent	event )
						{
							if ( destroyed ){
								
								timer_event.cancel();
								
							}else{
							
								checkTimeouts();
							}
						}
					});
			
	}
	
	@Override
	public boolean 
	isDisabled()
	{
		return( false );
	}
	
	protected void
	setTraceOn(
		boolean		b )
	{
		TRACE = b;
	}
	
	public DHTTransportContactI2P
	importContact(
		NodeInfo		node,
		boolean			is_bootstrap )
	{
		DHTTransportContactI2P	contact = new DHTTransportContactI2P( this, node, (byte)0, 0, 0, (byte)0 );
		
		request_handler.contactImported( contact, is_bootstrap );
		
		return( contact );
	}
	
	public DHTTransportContactI2P
	importContact(
		byte[]		hash,
		int			port,
		byte[]		id,
		int			version )
	{
		NodeInfo node = new NodeInfo( new NID( id), new Hash( hash ), port );
		
		DHTTransportContactI2P	contact = new DHTTransportContactI2P( this, node, (byte)version, 0, 0, (byte)0 );
		
		request_handler.contactImported( contact, false );
		
		return( contact );
	}
	
    	// these not relevant for muxed listener
    
    @Override
    public void
    messageAvailable(
    	I2PSession 	session, 
    	int 		msgId, 
    	long 		size )
    {
    }

    @Override
    public void
    reportAbuse(
    	I2PSession 	session, 
    	int 		severity )
    {
    }

    @Override
    public void
    disconnected(
    	I2PSession 	session )
    {
    }

    @Override
    public void
    errorOccurred(
    	I2PSession 	session, 
    	String 		message, 
    	Throwable 	error ) 
    {
    }
    
	@Override
	public byte
	getProtocolVersion()
	{
		return( DHTUtilsI2P.PROTOCOL_VERSION );
	}
	
	@Override
	public byte
	getMinimumProtocolVersion()
	{
		return( DHTUtilsI2P.PROTOCOL_VERSION_MIN );
	}
	
	@Override
	public int
	getNetwork()
	{
		return( DHTUtilsI2P.DHT_NETWORK );
	}

	@Override
	public boolean
	isIPV6()
	{
		return( false );
	}
	
	@Override
	public byte
	getGenericFlags()
	{
		return( generic_flags );
	}
	
	@Override
	public void
	setGenericFlag(
		byte		flag,
		boolean		value )
	{
		synchronized( this ){
			
			if ( value ){
				
				generic_flags |= flag;
				
			}else{
				
				generic_flags &= ~flag;
			}
		}
	}
	
	@Override
	public void
	setSuspended(
		boolean			susp )
	{
		
	}

	
	@Override
	public DHTTransportContact
	getLocalContact()
	{
		return( local_contact );
	}
	
	@Override
	public int
	getPort()
	{
		return( query_port );
	}
	
	public int
	getReplyPort()
	{
		return( reply_port );
	}
	
	@Override
	public void
	setPort(
		int	port )
	
		throws DHTTransportException
	{
		throw( new RuntimeException( "Not Supported" ));
	}
	
	@Override
	public long
	getTimeout()
	{
		return( request_timeout );
	}
	
	@Override
	public void
	setTimeout(
		long		millis )
	{
		request_timeout	= millis;
	}
	
	public DHTTransportFullStats
	getFullStats(
		DHTTransportContactI2P	contact )
	{
		if ( contact == local_contact ){
			
			return( request_handler.statsRequest( contact ));
		}
		
		Debug.out( "Status not supported for remote contacts" );
		
		return( null );
	}
	
	public Map<String,Object>
	exportContactToMap(
		DHTTransportContactI2P		contact )
	{
		NodeInfo		node = contact.getNode();
		
		Map<String,Object>	map = new HashMap<String, Object>();
		
		map.put( "n", node.getNID().getData());
				
		map.put( "p", node.getPort());
			
		map.put( "h", node.getHash().getData());
			
		Destination dest = node.getDestination();
				
		if ( dest != null ){
			
			map.put( "d", dest.toByteArray());
		}
				
		map.put( "v", contact.getProtocolVersion());
		
		return( map );
	}

	public DHTTransportContactI2P
	importContactFromMap(
		Map<String,Object>		map )
	{		
		byte[]	b_nid = (byte[])map.get( "n" );
		
		int port = ((Number)map.get("p")).intValue();
		
		byte[]	b_hash = (byte[])map.get( "h" );

		byte[]	b_dest = (byte[])map.get( "d" );

		NodeInfo node;
		
		if ( b_dest == null ){
			
			node = new NodeInfo( new NID(b_nid), new Hash( b_hash ), port );
			
		}else{
			
			Destination dest = new Destination();
			
			try{
				dest.fromByteArray( b_dest );
			
				node = new NodeInfo( new NID(b_nid), dest, port );
				
			}catch( DataFormatException e ){

				node = new NodeInfo( new NID(b_nid), new Hash( b_hash ), port );
			}		
		}
		
		int	contact_version = ((Number)map.get("v")).intValue();
		
		DHTTransportContactI2P contact = new DHTTransportContactI2P( this, node, (byte)contact_version, 0, 0, (byte)0 );
		
		request_handler.contactImported( contact, false );

		return( contact );	
	}
	
	public void
	exportContact(
		DataOutputStream			os,
		DHTTransportContactI2P		contact )
		
		throws IOException, DHTTransportException
	{
		NodeInfo		node = contact.getNode();
		
		os.writeByte( 1 );	// serialisation version
							// version 1: added contact version
		
		DHTUtilsI2P.serialiseByteArray( os, node.getNID().getData(), 255 );
		
		os.writeInt( node.getPort());
		
		DHTUtilsI2P.serialiseByteArray( os, node.getHash().getData(), 255 );
			
		Destination dest = node.getDestination();
		
		byte[] b_dest;
		
		if ( dest == null ){
			
			b_dest = new byte[0];
			
		}else{
			
			b_dest = dest.toByteArray();
		}
		
		DHTUtilsI2P.serialiseByteArray( os, b_dest, 512 );
		
		os.writeInt( contact.getProtocolVersion());
	}
		
	@Override
	public DHTTransportContact
	importContact(
		DataInputStream		is,
		boolean				is_bootstrap )
	
		throws IOException, DHTTransportException
	{
		byte serial_version = is.readByte();
		
		byte[]	b_nid = DHTUtilsI2P.deserialiseByteArray( is, 255 );
		
		int port = is.readInt();
		
		byte[]	b_hash = DHTUtilsI2P.deserialiseByteArray( is, 255 );

		byte[]	b_dest = DHTUtilsI2P.deserialiseByteArray( is, 512 );

		NodeInfo node;
		
		if ( b_dest.length == 0 ){
			
			node = new NodeInfo( new NID(b_nid), new Hash( b_hash ), port );
			
		}else{
			
			Destination dest = new Destination();
			
			try{
				dest.fromByteArray( b_dest );
			
			}catch( DataFormatException e ){
				
				throw( new IOException( e ));
			}
			
			node = new NodeInfo( new NID(b_nid), dest, port );
		}
		
		int	contact_version;
		
		if ( serial_version > 0 ){
			
			contact_version = is.readInt();
			
		}else{
			
			contact_version = 0;
		}
		
		DHTTransportContact contact = new DHTTransportContactI2P( this, node, (byte)contact_version, 0, 0, (byte)0 );
		
		request_handler.contactImported( contact, is_bootstrap );

		return( contact );
	}
	
	public void
	removeContact(
		DHTTransportContactI2P	contact )
	{
		request_handler.contactRemoved( contact );
	}
	
	@Override
	public void
	setRequestHandler(
		DHTTransportRequestHandler	_request_handler )
	{
		_request_handler	= new DHTTransportRequestCounter( _request_handler, stats );
		
		request_handler = _request_handler;
	}
	
	public void
	setAZRequestHandler(
		AZRequestHandler		_azrh )
	{
		az_request_handler = _azrh;
	}
	
	@Override
	public DHTTransportStatsI2P
	getStats()
	{
		return( stats );
	}
	
		// RPCs
	
	private HashMap<HashWrapper,Request>		requests = new HashMap<HashWrapper, Request>();
	
	public void
	sendPing(
		Destination		dest,
		int				port )
	{
			// used to help bootstrap 
		
		sendPing( new NodeInfo( dest, port ), false );
	}
	
	public boolean
	sendPing(
		NodeInfo		node )
	{
		return( sendPing( node, true ));
	}
	
	private boolean
	sendPing(
		NodeInfo		node,
		boolean			wait_for_reply )
	{
		final boolean[] result = { false };
		
		try{
	        stats.pingSent( null );
	
	        Map<String, Object> map = new HashMap<String, Object>();
	        	        
	        map.put( "q", "ping" );
	        
	        Map<String, Object> args = new HashMap<String, Object>();
	        
	        map.put( "a", args );
	        
	        final AESemaphore sem = new AESemaphore( "i2p:wait" );
	        
	        sendQuery( 
	        	new ReplyHandlerAdapter( null )
	        	{
	        		@Override
	        		public void 
	        		handleReply(
	        			int		originator_version,
	        			Map 	reply,
	        			int		elapsed ) 
	        		{	        			
	        			stats.pingOK();
	        			
	        			result[0] = true;
	        			
	        			sem.release();
	        		}
	        		
	        		@Override
	        		public void 
	        		handleError(
	        			DHTTransportException error) 
	        		{	        			
	        			stats.pingFailed();
	        			
	        			sem.release();
	        		}
	        	}, 
	        	node, map, RPC_TYPE_TWO_WAY, false, false );
	        
	        if ( wait_for_reply ){
	        
	        	sem.reserve();
	        }
	        
		}catch( Throwable e ){
		}
		
		return( result[0] );
	}
	
	public void
	sendPing(
		final DHTTransportReplyHandler		handler,
		final DHTTransportContactI2P		contact )
	{
		if ( TRACE ) trace( "sendPing" );
		
		try{
	        stats.pingSent( null );

	        Map<String, Object> map = new HashMap<String, Object>();
	        
	        map.put( "q", "ping" );
	        
	        Map<String, Object> args = new HashMap<String, Object>();
	        
	        map.put( "a", args );
	        
	        sendQuery( 
	        	new ReplyHandlerAdapter( contact )
	        	{
	        		@Override
	        		public void 
	        		handleReply(
	        			int		originator_version,
	        			Map 	reply,
	        			int		elapsed ) 
	        		{
	        			if ( TRACE ) trace( "good pingReply" );
	        			
	        			contact.setProtocolVersion((byte)originator_version );
	        			
	        			handler.pingReply( contact, elapsed );
	        			
	        			stats.pingOK();
	        		}
	        		
	        		@Override
	        		public void 
	        		handleError(
	        			DHTTransportException error) 
	        		{
	        			if ( TRACE ) trace( "error pingReply: " + Debug.getNestedExceptionMessage( error ));

	        			handler.failed( contact, error );
	        			
	        			stats.pingFailed();
	        		}
	        	}, 
	        	contact, map, RPC_TYPE_TWO_WAY, false, false );
	        	        
		}catch( Throwable e ){
			
			if ( e instanceof DHTTransportException ){
				
				handler.failed( contact, (DHTTransportException)e) ;
				
			}else{
				
				handler.failed( contact, new DHTTransportException( "ping failed", e )) ;
			}
		}
    }
    
	private void
	receivePing(
		DHTTransportContactI2P		originator,
		byte[]						message_id )
		
		throws Exception
	{
		if ( TRACE ) trace( "receivePing" );

		if ( request_handler == null ){
			
			throw( new Exception( "No request handler available" ));
		}
		
		request_handler.pingRequest( originator );

		Map<String, Object> map = new HashMap<String, Object>();
		
		Map<String, Object> resps = new HashMap<String, Object>();
		
		map.put("r", resps);
		
		sendResponse( originator, message_id, map, true );
	}
	   
	public boolean
	sendFindNode(
		NodeInfo		node,
		byte[]			target )
	{
		final boolean[] result = { false };
		
		try{
	        stats.findNodeSent( null );
	
	        Map<String, Object> map = new HashMap<String, Object>();
	        
	        map.put( "q", "find_node" );
	        
	        Map<String, Object> args = new HashMap<String, Object>();
	        
	        map.put( "a", args );
	        
	        args.put( "target", target );
	        
	        final AESemaphore sem = new AESemaphore( "i2p:wait" );
	        
	        sendQuery( 
	        	new ReplyHandlerAdapter( null )
	        	{
	        		@Override
	        		public void 
	        		handleReply(
	        			int		originator_version,
	        			Map 	reply,
	        			int		elapsed )
	        		{
	        			byte[]	nodes = (byte[])reply.get( "nodes" );
	        				        				        			
	        			for ( int off = 0; off < nodes.length; off += NodeInfo.LENGTH ){
	        				
	        				NodeInfo node = new NodeInfo( nodes, off );
	        				
	        				request_handler.contactImported( new DHTTransportContactI2P( DHTTransportI2PRealImpl.this, node, (byte)0, 0, 0, (byte)0 ), false );
	        			}
	        			
	        			stats.findNodeOK();
	        			
	        			result[0] = true;
	        			
	        			sem.release();
	        		}
	        		
	        		@Override
	        		public void 
	        		handleError(
	        			DHTTransportException error) 
	        		{
	        			
	        			stats.findNodeFailed();
	        			
	        			sem.release();
	        		}
	        	}, 
	        	node, map, RPC_TYPE_TWO_WAY, true, false );
	        
	        sem.reserve();
	        
		}catch( Throwable e ){
		}
		
		return( result[0] );
	}
	
	public void
	sendFindNode(
		final DHTTransportReplyHandler		handler,
		final DHTTransportContactI2P		contact,
		byte[]								target,
		short								flags )
	{
		if ( TRACE ) trace( "sendFindNode, flags=" + flags );
		
		boolean priority = (flags&I2PHelperAZDHT.FLAG_HIGH_PRIORITY) != 0;

		if (( flags & DHT.FLAG_LOOKUP_FOR_STORE ) != 0 ){
			
				// only way tokens get allocated is via findValue (get_peers)
				// so we frig this by creating an obfuscated derived key that really shouldn't
				// happen to return anything other than peers...
			
			byte[] new_target = new byte[target.length];
						
			RandomUtils.nextBytes( new_target );
			
			System.arraycopy( target, 0, new_target, 0, target.length - 8 );
			
			sendFindValue( 
				new DHTTransportReplyHandlerAdapter()
				{
					@Override
					public void
					findValueReply(
						DHTTransportContact 	contact,
						DHTTransportValue[]		values,
						byte					diversification_type,
						boolean					more_to_come )
					{
						handler.findNodeReply( contact, new DHTTransportContact[0] );
					}
					
					@Override
					public void
					findValueReply(
						DHTTransportContact 	contact,
						DHTTransportContact[]	contacts )
					{
						handler.findNodeReply( contact, contacts );
					}
					
					@Override
					public void
					failed(
						DHTTransportContact 	contact,
						Throwable 				error) 
					{
						handler.failed( contact, error );
					}
				},
				contact,
				new_target,
				flags,
				priority );
			
		}else{
			
			try{
		        stats.findNodeSent( null );
	
		        Map<String, Object> map = new HashMap<String, Object>();
		        
		        map.put( "q", "find_node" );
		        
		        Map<String, Object> args = new HashMap<String, Object>();
		        
		        map.put( "a", args );
		        
		        args.put( "target", target );
		        
		        sendQuery( 
		        	new ReplyHandlerAdapter( contact )
		        	{
		        		@Override
		        		public void 
		        		handleReply(
		        			int		originator_version,
		        			Map 	reply,
		        			int		elapsed )
		        		{
		        			contact.setProtocolVersion((byte)originator_version );
		        			
		        			if ( TRACE ) trace( "good findNodeReply: " + reply );
		        				        			
		        			/* no token on findNode
		        			byte[]	token = (byte[])reply.get( "token" );
		        			if ( token != null ){
		        			}
		        			*/
		        			
		        			byte[]	nodes = (byte[])reply.get( "nodes" );
		        			
		        			DHTTransportContactI2P[]	contacts  = new DHTTransportContactI2P[nodes.length/NodeInfo.LENGTH];
		        			
		        			int	pos = 0;
		        			
		        			for ( int off = 0; off < nodes.length; off += NodeInfo.LENGTH ){
		        				
		        				NodeInfo node = new NodeInfo( nodes, off );
		        				
		        				contacts[pos++] = new DHTTransportContactI2P( DHTTransportI2PRealImpl.this, node, (byte)0, 0, 0, (byte)0 );
		        			}
		        			
		        			handler.findNodeReply( contact, contacts );
		        			
		        			stats.findNodeOK();
		        		}
		        		
		        		@Override
		        		public void 
		        		handleError(
		        			DHTTransportException error) 
		        		{
		        			if ( TRACE ) trace( "error findNodeReply: " + Debug.getNestedExceptionMessage( error ));
	
		        			handler.failed( contact, error );
		        			
		        			stats.findNodeFailed();
		        		}
		        	}, 
		        	contact, map, RPC_TYPE_TWO_WAY, priority, false );
		        	        
			}catch( Throwable e ){
				
				if ( e instanceof DHTTransportException ){
					
					handler.failed( contact, (DHTTransportException)e) ;
					
				}else{
					
					handler.failed( contact, new DHTTransportException( "findNode failed", e )) ;
				}
			}
		}
    }
    
	private void
	receiveFindNode(
		DHTTransportContactI2P		originator,
		byte[]						message_id,
		byte[]						target )
		
		throws Exception
	{
		if ( TRACE ) trace( "receiveFindNode" );

		if ( request_handler == null ){
			
			throw( new Exception( "No request handler available" ));
		}
		
		DHTTransportContact[] contacts = request_handler.findNodeRequest( originator, target );

		if ( contacts != null ){
			
			Map<String, Object> map = new HashMap<String, Object>();
			
			Map<String, Object> resps = new HashMap<String, Object>();
			
			map.put( "r", resps);
	
			// no token returned for find-node, just find-value
			// byte[] token = originator.getRandomID2();				
			// resps.put( "token", token );
			
	        byte[] nodes = new byte[contacts.length * NodeInfo.LENGTH];
	        
	        for ( int i=0; i<contacts.length; i++ ){
	        	
	            System.arraycopy(((DHTTransportContactI2P)contacts[i]).getNode().getData(), 0, nodes, i * NodeInfo.LENGTH, NodeInfo.LENGTH);
	        }
	        
			resps.put( "nodes", nodes );
			
			sendResponse( originator, message_id, map, true );
		}
	}
	
	
	public void
	sendFindValue(
		final DHTTransportReplyHandler		handler,
		final DHTTransportContactI2P		contact,
		byte[]								target,
		short								flags,
		boolean								priority )
	{
		if ( TRACE ) trace( "sendFindValue: contact=" + contact.getString() + ", target=" + ByteFormatter.encodeString( target ));
		
		try{
	        stats.findValueSent( null );

	        Map<String, Object> map = new HashMap<String, Object>();
	        
	        map.put( "q", "get_peers" );
	        
	        Map<String, Object> args = new HashMap<String, Object>();
	        
	        map.put( "a", args );
	        
	        args.put( "info_hash", target );
	        
	        if ( ( flags & DHT.FLAG_SEEDING ) != 0 ){
	        	
	        	args.put( "noseed", new Long(1));
	        }
	        
	        sendQuery( 
	        	new ReplyHandlerAdapter( contact )
	        	{
	        		@Override
	        		public void 
	        		handleReply(
	        			int		originator_version,
	        			Map 	reply,
	        			int		elapsed )
	        		{
	        			if ( TRACE ) trace( "good sendFindValue: " + reply );
	        			
	        			contact.setProtocolVersion((byte)originator_version );
	        			
	        			byte[]	token = (byte[])reply.get( "token" );
	        			
	        			if ( token != null ){
	        			
	        				contact.setRandomID2( token );
	        			}
	        			
	        			byte[]	nodes = (byte[])reply.get( "nodes" );
	        			
	        			if ( nodes != null ){
	        				
		        			DHTTransportContactI2P[]	contacts  = new DHTTransportContactI2P[nodes.length/NodeInfo.LENGTH];
		        			
		        			int	pos = 0;
		        			
		        			for ( int off = 0; off < nodes.length; off += NodeInfo.LENGTH ){
		        				
		        				NodeInfo node = new NodeInfo( nodes, off );
		        				
		        				contacts[pos++] = new DHTTransportContactI2P( DHTTransportI2PRealImpl.this, node, (byte)0, 0, 0, (byte)0 );
		        			}
		        			
		        			handler.findValueReply( contact, contacts );
		        			
	        			}else{
	        			
	        				List<byte[]> peers = (List<byte[]>)reply.get( "values");
	        				
	        				if ( peers == null ){
	        					
	        					peers = new ArrayList<byte[]>(0);
	        				}
	        				
	        				DHTTransportValue[] values = new DHTTransportValue[peers.size()];
	        				
	        				byte[]	flags = (byte[])reply.get( "flags" );
	        				
	        				for ( int i=0;i<values.length;i++){
	        					
	        					short flag = DHT.FLAG_DOWNLOADING;
	        					
	        					if ( flags != null ){
	        						
	        						if ((flags[i/8] & (1<<(7-(i%8)))) != 0 ){
	        							
	        							flag = DHT.FLAG_SEEDING;
	        						}
	        					}
	        					
	        					values[i] = new DHTTransportValueImpl( contact, flag, peers.get(i));
	        				}
	        				
	        				handler.findValueReply( contact, values, DHT.DT_NONE, false );
	        			}
	        			
	        			stats.findValueOK();
	        		}
	        		
	        		@Override
	        		public void 
	        		handleError(
	        			DHTTransportException error) 
	        		{
	        			if ( TRACE ) trace( "error sendFindValue: " + Debug.getNestedExceptionMessage( error ));

	        			handler.failed( contact, error );
	        			
	        			stats.findValueFailed();
	        		}
	        	}, 
	        	contact, map, RPC_TYPE_TWO_WAY, priority, false );
	        	        
		}catch( Throwable e ){
			
			if ( e instanceof DHTTransportException ){
				
				handler.failed( contact, (DHTTransportException)e) ;
				
			}else{
				
				handler.failed( contact, new DHTTransportException( "findValue failed", e )) ;
			}
		}
    }
	
	
	private void
	receiveFindValue(
		DHTTransportContactI2P		originator,
		byte[]						message_id,
		byte[]						hash,
		boolean						no_seed )
		
		throws Exception
	{
		if ( TRACE ) trace( "receiveFindValue" );

		if ( request_handler == null ){
			
			throw( new Exception( "No request handler available" ));
		}
		
		DHTTransportFindValueReply reply = request_handler.findValueRequest( originator, hash, NUM_WANT, (byte)0 );

		if ( reply != null ){
			
			Map<String, Object> map = new HashMap<String, Object>();
			
			Map<String, Object> resps = new HashMap<String, Object>();
			
			map.put( "r", resps);
	
			byte[] token = originator.getRandomID2();
							
			resps.put( "token", token );
			
			NodeInfo node = originator.getNode();
			
			synchronized( token_map ){
				
				token_map.put( new HashWrapper( token ), node );
			}
			
			if ( reply.hit()){
				
				DHTTransportValue[] values = reply.getValues();
				
				List<byte[]>	peers = new ArrayList<byte[]>( values.length );
				
				byte[]	caller_hash = originator.getNode().getHash().getData();
	
				boolean	caller_non_vuze = originator.getProtocolVersion() == DHTUtilsI2P.PROTOCOL_VERSION_NON_VUZE;
	
				if ( caller_non_vuze ){
					
	
						// Snark removes the peer itself from the list returned so a peer can't read its own
						// values stored at a node. This in itself isn't so bad, but what is worse is that
						// Snark will end up returning "nodes" if there was only the one value stored which
						// results in an exhaustive search by the caller in the case where there is only
						// one announcer.... I'm not going to do this
									
					for ( DHTTransportValue value: values ){
						
						if ( no_seed && ( value.getFlags() & DHT.FLAG_SEEDING ) != 0 ){
							
							continue;
						}
						
						byte[]	peer_hash = value.getValue();
						
						if ( !Arrays.equals( caller_hash, peer_hash )){
							
							peers.add( value.getValue());
						}
					}
				}else{
					
					byte[]	flags = new byte[(values.length+7)/8];
					
					int	pos = 0;
					
					for ( DHTTransportValue value: values ){
						
						boolean is_seed = ( value.getFlags() & DHT.FLAG_SEEDING ) != 0;
	
							// for Vuze callers we don't remove the caller from the reply set
												
						peers.add( value.getValue());
							
						if ( is_seed ){
								
							flags[pos/8] |= 1<<(7-(pos%8));
						}
							
						pos++;
					}
									
					resps.put( "flags", flags );
				}
				
				resps.put( "values", peers );
				
			}else{
				
				DHTTransportContact[] contacts = reply.getContacts();
				
		        byte[] nodes = new byte[contacts.length * NodeInfo.LENGTH];
		        
		        for ( int i=0; i<contacts.length; i++ ){
		        	
		            System.arraycopy(((DHTTransportContactI2P)contacts[i]).getNode().getData(), 0, nodes, i * NodeInfo.LENGTH, NodeInfo.LENGTH);
		        }
		        
				resps.put( "nodes", nodes );
			}
			
			if ( TRACE ) trace( "    findValue->" + map);
	
			sendResponse( originator, message_id, map, true );
		}
	}
	
	public void
	sendStore(
		final DHTTransportReplyHandler	handler,
		final DHTTransportContactI2P	contact,
		final byte[][]					keys,
		final DHTTransportValue[][]		value_sets )
	{
		try{
			byte[]	token = contact.getRandomID2();
			
			if ( TRACE ) trace( "sendStore: keys=" + keys.length + ", token=" + token + ", contact=" + contact.getString());
			
			if ( token == null || token == DHTTransportContactI2P.DEFAULT_TOKEN ){

				throw( new DHTTransportException( "No token available for store operation" ));
			}

				// default expiry for these is 10 minutes for non-vuze peers
			
			long	token_age = contact.getRandomID2Age();
			
			if ( TRACE ) trace( "Token age: " + token_age + ", token=" + ByteFormatter.encodeString( token ));
			
			if ( 	contact.getProtocolVersion() == DHTUtilsI2P.PROTOCOL_VERSION_NON_VUZE &&
					token_age > 9*60*1000 + 30*1000 ){
				
				byte[] nid = contact.getID();
				
				byte[] new_target = new byte[nid.length];
				
				RandomUtils.nextBytes( new_target );
				
				System.arraycopy( nid, 0, new_target, 0, nid.length - 8 );
				
				sendFindValue( 
					new DHTTransportReplyHandlerAdapter()
					{
						private boolean complete;
						
						@Override
						public void
						findValueReply(
							DHTTransportContact 	contact,
							DHTTransportValue[]		values,
							byte					diversification_type,
							boolean					more_to_come )
						{
							done();
						}
						
						@Override
						public void
						findValueReply(
							DHTTransportContact 	contact,
							DHTTransportContact[]	contacts )
						{
							done();
						}
						
						@Override
						public void
						failed(
							DHTTransportContact 	contact,
							Throwable 				error) 
						{
							synchronized( this ){
								
								if ( complete ){
									
									return;
								}
								
								complete = true;
							}

							handler.failed( contact, new DHTTransportException( "sendStore token refresh failed", error )) ;
						}
						
						private void
						done()
						{
							synchronized( this ){
								
								if ( complete ){
									
									return;
								}
								
								complete = true;
							}
							
							long	token_age = contact.getRandomID2Age();
							
							if ( TRACE ) trace( "Refreshed Token age: " + token_age + ", token=" + ByteFormatter.encodeString( contact.getRandomID2()));

							sendStoreSupport( handler, contact, keys, value_sets );
						}
					},
					contact,
					new_target,
					DHT.FLAG_NONE,
					false );
			}else{
				
				sendStoreSupport( handler, contact, keys, value_sets );
			}	
		}catch( Throwable e ){

			if ( e instanceof DHTTransportException ){

				handler.failed( contact, (DHTTransportException)e) ;

			}else{

				handler.failed( contact, new DHTTransportException( "sendStore failed", e )) ;
			}
		}	
	}
	
	private void
	sendStoreSupport(
		final DHTTransportReplyHandler	handler,
		final DHTTransportContactI2P	contact,
		byte[][]						keys,
		DHTTransportValue[][]			value_sets )
	{				
		try{
			byte[]	token = contact.getRandomID2();

			if ( token == null || token == DHTTransportContactI2P.DEFAULT_TOKEN ){

				throw( new DHTTransportException( "No token available for store operation" ));
			}

			stats.storeSent( null );

			for ( int i=0;i<keys.length;i++){

				byte[]					key		= keys[i];
				DHTTransportValue[]		values 	= value_sets[i];
			
				final boolean	report_result = (i==keys.length-1);

					// we're not republishing cached values (yet) so there should only be a single
					// (originator) value
				
				if ( values[0].getValue().length == 0 ){
					
						// this is actually un-announce (entries are removed by sending zero length values )
					
						// if/when in the future we want to support more generic DHT storage then obviously
						// we'll need to revisit this whole area
					
					if ( report_result ){
						
						handler.storeReply( contact, new byte[]{ DHT.DT_NONE });

						stats.storeOK();
					}
				}else{
					
					try{
						Map<String, Object> map = new HashMap<String, Object>();
	
						map.put( "q", "announce_peer" );
	
						Map<String, Object> args = new HashMap<String, Object>();
	
						map.put( "a", args );
	
						if ( TRACE ) trace( "   storeKey: " + ByteFormatter.encodeString( key ));
						
						args.put( "info_hash", key );
	
						args.put( "port", 6881 );		// not used but for completeness
	
						args.put( "token", token );
	
						boolean	seed =  ( values[0].getFlags() & DHT.FLAG_SEEDING ) != 0;
							
						args.put( "seed", new Long(seed?1:0));
											
						sendQuery( 
								new ReplyHandlerAdapter( contact )
								{
									@Override
									public void 
									handleReply(
										int		originator_version,
										Map 	reply,
										int		elapsed )
									{
										if ( TRACE ) trace( "good sendStoreReply" );
	
										contact.setProtocolVersion((byte)originator_version );
										
										if ( report_result ){
	
											handler.storeReply( contact, new byte[]{ DHT.DT_NONE });
	
											stats.storeOK();
										}
									}
	
									@Override
									public void 
									handleError(
											DHTTransportException error) 
									{
										if ( TRACE ) trace( "error sendStoreReply: " + Debug.getNestedExceptionMessage( error ));
	
										if ( report_result ){
	
											handler.failed( contact, error );
	
											stats.storeFailed();
										}
									}
								}, 
								contact, map, RPC_TYPE_UNREPLIABLE, false, false );		// NOT repliable. Note however that we still get a reply as the target has (or should have) our dest cached against the token...
	
					}catch( Throwable e ){
	
						if ( report_result ){
	
							throw( e );
						}
					}
				}
			}     
		}catch( Throwable e ){

			if ( e instanceof DHTTransportException ){

				handler.failed( contact, (DHTTransportException)e) ;

			}else{

				handler.failed( contact, new DHTTransportException( "sendStore failed", e )) ;
			}
		}	
	}
	
	private void
	receiveStore(
		DHTTransportContactI2P		originator,
		byte[]						message_id,
		byte[]						hash,
		boolean						is_seed )
		
		throws Exception
	{
		if ( TRACE ) trace( "receiveStore" );
		
		if ( request_handler == null ){
			
			throw( new Exception( "No request handler available" ));
		}
		
		byte[][]				keys 	= new byte[][]{ hash };
		
		DHTTransportValue value = 
				new DHTTransportValueImpl( originator, is_seed?DHT.FLAG_SEEDING:DHT.FLAG_DOWNLOADING, originator.getNode().getHash().getData());
		
		
		DHTTransportValue[][]	values 	= new DHTTransportValue[][]{{ value }};
		
		request_handler.storeRequest( originator, keys, values );
		
		Map<String, Object> map = new HashMap<String, Object>();
		
		Map<String, Object> resps = new HashMap<String, Object>();
		
		map.put( "r", resps);
		
		sendResponse( originator, message_id, map, true );
	}
	
	
	public void
	sendAZRequest(
		final AZReplyHandler				handler,
		final DHTTransportContactI2P		contact,
		boolean								reply_expected,
		boolean								priority,
		Map<String, Object>					payload,
		boolean								override_sleeping )
	{
		if ( TRACE ) trace( "sendAZRequest: " + payload );
		
		try{
	        Map<String, Object> map = new HashMap<String, Object>();
	        
	        map.put( "q", "azrequest" );
	        
	        Map<String, Object> args = new HashMap<String, Object>();
	        
	        map.put( "a", args );
	        
	        if ( payload != null ){
	        
	        	args.put( "p", payload );
	        }
	        
	        byte[] token = contact.getRandomID2();
	        
	        if ( token != null ){
	        	
	        	args.put( "token", token );
	        }
	        
        	sendQuery( 
	        	new ReplyHandlerAdapter( contact )
	        	{
	        		@Override
	        		public void 
	        		packetSent(
	        			int length ) 
	        		{
	        			handler.packetSent( length );
	        		}
	        		
	        		@Override
	        		public void 
	        		packetReceived(
	        			int length ) 
	        		{
	        			handler.packetReceived( length );
	        		}
	        		
	        		@Override
	        		public void 
	        		handleReply(
	        			int					originator_version,
	        			Map<String,Object> 	reply,
	        			int					elapsed )
	        		{
	        			if ( TRACE ) trace( "good AZReply" );
	        			
	        			contact.setProtocolVersion((byte)originator_version );
	        			
	        			byte[] token = (byte[])reply.get( "token" );
	        			
	        			if ( token != null ){
	        				
	        				contact.setRandomID2( token );
	        			}
	        			
	        			handler.reply( contact, (Map<String,Object>)reply.get( "p" ), elapsed );
	        		}
	        		
	        		@Override
	        		public void 
	        		handleError(
	        			DHTTransportException error) 
	        		{
	        			if ( TRACE ) trace( "error AZReply: " + Debug.getNestedExceptionMessage( error ));

	        			handler.failed( contact, error );
	        		}
	        	}, 
	        	contact, map, reply_expected?RPC_TYPE_TWO_WAY:RPC_TYPE_ONE_WAY, priority, override_sleeping );
	        	        
		}catch( Throwable e ){
			
			if ( e instanceof DHTTransportException ){
				
				handler.failed( contact, (DHTTransportException)e) ;
				
			}else{
				
				handler.failed( contact, new DHTTransportException( "AZRequest failed", e )) ;
			}
		}
    }
	
	protected void 
	receiveAZRequest(
		DHTTransportContactI2P		originator,
		int							packet_length,
		byte[]						message_id,
		Map<String,Object>			args )
		
		throws Exception
	{		
		Map<String, Object>	payload_in = (Map<String, Object>)args.get( "p" );
	
		if ( TRACE ) trace( "receiveAZRequest: " + payload_in );

		if ( az_request_handler == null ){
			
			throw( new Exception( "No request handler available" ));
		}
		
		az_request_handler.packetReceived( packet_length );
		
		byte[]	token_in = (byte[])args.get( "token" );
		
		if ( token_in != null ){
			
			originator.setRandomID2( token_in );
		}
		
		AZRequestResult	result = az_request_handler.receiveRequest( originator, payload_in );
				
		if ( result != null ){
			
				// dispatch reply
			
			Map<String, Object> map = new HashMap<String, Object>();
			
			Map<String, Object> resps = new HashMap<String, Object>();
			
			map.put( "r", resps);
					
			byte[] token_out = (byte[])originator.getRandomID2();
			
			if ( token_out != null ){
				
				if ( token_in == null || !Arrays.equals( token_in, token_out )){
			
					resps.put( "token", token_out );
				}
			}
			
			Map<String, Object> payload_out = result.getReply();
			
			resps.put( "p", payload_out );
			
			int sent = sendResponse( originator, message_id, map, result.isAdHoc());
			
			az_request_handler.packetSent( sent );
		}
	}
	
		// -------------
	
	public boolean
	lookupDest(
		NodeInfo		node )
	{
		try{
				// blocking ok here as this method only used for bootstrap test logic which is
				// already async. Also we want to force the lookup regardless of whether or not
				// we have a dest
			
			Destination dest = session.lookupDest( node.getHash(), DHTUtilsI2P.DEST_LOOKUP_TIMEOUT );
        
            if ( dest != null ){
            
                node.setDestination(dest);
                
                return( true );
            }
		}catch( Throwable e ){
		}
		
		return( false );
	}
	
	private void 
    sendQuery(
    	ReplyHandlerAdapter			handler,
    	DHTTransportContactI2P		contact,
    	Map					 		map, 
    	int		 					rpc_type,
    	boolean						priority ,
    	boolean						override_sleeping )
    	
    	throws Exception
    {
		if ((!override_sleeping) && contact.isSleeping()){
			
			throw( new DHTTransportException( "Contact is sleeping, request denied: " + map ));
		}

	   	NodeInfo node = contact.getNode();
	   
		sendQuery( handler, node, map, rpc_type, priority, override_sleeping );
    }
   
    private void 
    sendQuery(
    	final ReplyHandlerAdapter		handler,
    	final NodeInfo					node,
    	final Map				 		map, 
    	final int	 					rpc_type,
    	final boolean					priority,
    	final boolean					override_sleeping )
    	
    	throws Exception
    {
    	if ( session.isClosed()){
    		
           	throw( new DHTTransportException( "Session closed" ));
    	}
    	   	
    	Destination	dest = node.getDestination();
    	
    	if ( dest == null ){
    		
    			// shortcut 'anonymous' contacts (zero hash and port of 1)
    		
    		if ( node.getPort() == 1 ){
    			
    			boolean ok = false;
    			
    			byte[] hash = node.getHash().getData();
    		
    			for ( byte b: hash ){
    				if ( b != 0 ){
    					ok = true;
    					break;
    				}
    			}
    			
    			if ( !ok ){
    				
    				throw( new DHTTransportException( "NodeInfo denotes 'anonymous' contact" ));
    			}
    		}
    		
    		ThreadPool pool = priority?destination_lookup_pool_hp:destination_lookup_pool_lp;
    		
    		if ( TRACE ) trace( "Scheduling dest lookup: active=" + pool.getRunningCount() + ", queued=" + pool.getQueueSize() + ", priority=" + priority );

    		pool.run(
    			new AERunnable() 
    			{
					@Override
					public void
					runSupport() 
					{
						try{
							byte[] hash = node.getHash().getData();
							
							int i1 = 	((hash[0] & 0xff) << 24) | ((hash[1] & 0xff) << 16) |
										((hash[2] & 0xff) << 8)  | (hash[3] & 0xff);
							
							int i2 = 	((hash[4] & 0xff) << 24) | ((hash[5] & 0xff) << 16) |
										((hash[6] & 0xff) << 8)  | (hash[7] & 0xff);

							long l_hash = ((i1 & 0xffffffffL) << 32) | (i2 & 0xffffffffL);

							long	start = SystemTime.getMonotonousTime();

							synchronized( dest_lookup_negative_cache ){
								
								DestLookupNegativeCacheEntry entry = dest_lookup_negative_cache.get( l_hash );
								
								if ( entry != null ){
																		
									int num_fails = entry.num_fails;
									
									int	delay = 60*1000;
											
									for ( int i=1;i<num_fails;i++){
										
										delay *= 2;
										
										if ( delay > 14*60*1000 ){
											
											delay = 14*60*1000;
											
											break;
										}
									}
									
									if ( start < entry.first_fail + delay ){
										
										if ( TRACE_DEST_LOOKUPS ){

											System.out.println( ByteFormatter.encodeString(hash, 0, 8) + ": denied (" + num_fails + ")" );
										}
										
										throw( new DHTTransportException( "Destination lookup failed (negative cache)" ));
										
									}else{
										
										entry.first_fail = start;
									}
								}
							}
							
							if ( TRACE_DEST_LOOKUPS ){
								
								synchronized( dest_lookup_rate ){
									
									dest_lookup_rate.addValue(1);
								
									dest_lookup_count++;
									
									if ( start - dest_lookup_count_log > 10*1000 ){
										
										dest_lookup_count_log = start;
	
										String lta = "";
										
										if ( dest_lookup_count_start == 0 ){
											
											dest_lookup_count_start = start;
											
										}else{
											
											long elapsed = start-dest_lookup_count_start;
											
											lta = " (" + (dest_lookup_count*1000f)/(elapsed) + ", " + elapsed/(60*1000) + ")";
										}
	
										System.out.println( dest_lookup_rate.getAverage() + ", " + dest_lookup_count + lta + ": hist=" + dest_lookup_tracker.size() + "/" + dest_lookup_consec_fail_count + "/" + dest_lookup_consec_fail_60_count  );
									}
								}
							}
							
							
							//dest_lookup_negative_cache
							
							Destination dest = null;
							
							try{
								dest = session.lookupDest( node.getHash(), DHTUtilsI2P.DEST_LOOKUP_TIMEOUT );
								
							}finally{
								
								synchronized( dest_lookup_negative_cache ){

									if ( dest == null ){
									
										DestLookupNegativeCacheEntry entry = dest_lookup_negative_cache.get( l_hash );
										
										if ( entry == null ){
											
											dest_lookup_negative_cache.put( l_hash, new DestLookupNegativeCacheEntry( start, 1 ));
											
										}else{
											
											entry.num_fails++;
										}
									}else{
										
										dest_lookup_negative_cache.remove( l_hash );
									}
								}

								if ( TRACE_DEST_LOOKUPS ){
									
	
									synchronized( dest_lookup_rate ){
										
										String mark = dest==null?".":"X";
										
										String[] hit = dest_lookup_tracker.get( l_hash );
										
										if ( hit == null ){
											
											hit = new String[]{ mark, "0", "" };
											
											dest_lookup_tracker.put( l_hash, hit );
											
										}else{
											
											hit[0] += mark;
										}
										
										long now = SystemTime.getMonotonousTime();
											
										if ( dest == null ){
											
											System.out.println( "fail in " + (( now - start )/1000 ));
										}
										String hist = hit[0];
										
										String extra = "";
										
										if ( hist.endsWith( ".." )){
											
											dest_lookup_consec_fail_count++;
											
											long prev_fail = Long.parseLong( hit[1]);
											
											long secs = (now - prev_fail )/1000;
											
											if ( secs > 60 ){
												
												dest_lookup_consec_fail_60_count++;
											}
											
											String s = hit[2];
											
											s += (s.length()==0?"":",") + secs;
											
											hit[2] = s;
											
											extra = " (" + s + ")";
										}
										
										if ( dest == null ){
											
											hit[1] = String.valueOf( now );
										}

										System.out.println( ByteFormatter.encodeString(hash, 0, 8) + ": " + hit[0] + extra);
									}
								}
							}
							
							if ( dest != null ){
            
								if ( TRACE ) trace( "Destination lookup ok - elapsed=" + (SystemTime.getMonotonousTime()-start));

								node.setDestination( dest );
								
					    		sendQuery( handler, dest, node.getPort(), map, rpc_type, override_sleeping );
					    		
							}else{
								
								throw( new DHTTransportException( "Destination lookup failed" ));
							}
						}catch( Throwable e ){
							
							if ( e instanceof DHTTransportException ){
								
								handler.handleError((DHTTransportException)e);
								
							}else{
								
								handler.handleError( new DHTTransportException( "Destination lookup failed", e ));
							}
						}
					}
    			});
    		    		
    	}else{
    		
    		sendQuery( handler, dest, node.getPort(), map, rpc_type, override_sleeping );
    	}
    }
    
    private void 
    sendQuery(
    	ReplyHandlerAdapter			handler,
    	Destination					dest,
    	int							port,
    	Map				 			map, 
    	int		 					rpc_type,
    	boolean						override_sleeping )
    	
    	throws Exception
    {
    	
	    map.put( "y", "q" );
	
	    	// i2p uses 8 byte random message ids and supports receiving up to 16 byte ids
	
	    byte [] msg_id = new byte[8];
	
	    RandomUtils.nextBytes( msg_id );
	
	    map.put("t", msg_id );
	
	    if ( override_sleeping ){
	    	
	    	map.put( "z", 0 );
	    }
	    
	    Map<String, Object> args = (Map<String, Object>) map.get("a");
	
	    args.put("id", my_nid.getData());
	    	 
	    encodeVersion( args );
	    
	    if ( rpc_type == RPC_TYPE_UNREPLIABLE ){
	    	
	    	port++;
	    }
	    
    	synchronized( requests ){
	    		
    		if ( destroyed ){
    			
    			throw( new DHTTransportException( "Transport destroyed" ));
    		}
    		 
    			// we treat unreliable as two way as for Vuze peers we will send a reply if we already have a resolved destination available (which we should do as 
    			// we have got a token from them recently...)
    		
    		if ( rpc_type != RPC_TYPE_ONE_WAY ){
    		
    			requests.put( new HashWrapper( msg_id ), new Request( dest, handler ));
    		}
	    }
	    
	    boolean	ok = false;
	    
	    try{
	    		// override sleeping is used for data requests so we assume they are not adhoc
	    	
	    	int res = sendMessage( dest, port, map, rpc_type, !override_sleeping );
	    	
	    	ok	= true;
	    	
	    	try{
	    		handler.packetSent( res );
	    		
	    	}catch( Throwable e ){
	    	}
	    	
	    }finally{
	    	
	    	if ( !ok ){
	    	
	    		synchronized( requests ){
	    		
	    			requests.remove( new HashWrapper( msg_id ));
	    		}
	    	}
	    }
	}
    
    private int
    sendResponse(
    	DHTTransportContactI2P 		originator, 
    	byte[]						message_id, 
    	Map							map,
    	boolean						adhoc )
    	
    	throws Exception
    {
    	NodeInfo node = originator.getNode();
    	
        Destination dest = node.getDestination();
        
        if ( dest == null ){
        	
        	Debug.out( "Hmm, destination is null" );
        	
        	return( 0 );
        }
        
        map.put( "y", "r" );
        
        map.put( "t", message_id );
        
        Map<String, Object> resps = (Map<String, Object>) map.get("r");
        
        resps.put( "id", my_nid.getData());
        
        encodeVersion( resps );
        
        return( sendMessage( dest, node.getPort() + 1, map, RPC_TYPE_UNREPLIABLE, adhoc ));
    }
    
    private void
    trace(
    	Destination		dest,
    	String			str )
    {
    	if ( trace_addresses != null && dest != null){
    	
    		String address = Base32.encode( dest.calculateHash().getData()) + ".b32.i2p";
    	
    		if ( trace_addresses.contains( address)){
    			
    			System.out.println( address + " - " + str );
    		}
    	}
    }
    
    private static final int SEND_CRYPTO_TAGS 	= 8;
    private static final int LOW_CRYPTO_TAGS 	= 4;

    private int 
    sendMessage(
    	Destination 			dest, 
    	int 					toPort, 
    	Map					 	map, 
    	int		 				rpc_type,
    	boolean					adhoc )
    	
    	throws Exception
    {

        byte[] payload = BEncoder.encode( map );
        
        	// Always send query port, peer will increment for unsigned replies
        
        int fromPort = query_port;
        
        if ( rpc_type != RPC_TYPE_UNREPLIABLE ){
        	
            I2PDatagramMaker dgMaker = new I2PDatagramMaker( session );
            
            payload = dgMaker.makeI2PDatagram( payload );
            
            if ( payload == null ){
               
            	throw( new DHTTransportException( "Datagram construction failed" ));
            }
        }

        SendMessageOptions opts = new SendMessageOptions();
        
        opts.setDate( SystemTime.getCurrentTime() + 60*1000);
        
        if ( adhoc ){
        
        	opts.setTagsToSend(SEND_CRYPTO_TAGS);       
        	opts.setTagThreshold(LOW_CRYPTO_TAGS);
        }
        
        if ( rpc_type == RPC_TYPE_UNREPLIABLE ){
        	
            opts.setSendLeaseSet( false );
        }
        
        stats.total_packets_sent++;
        stats.total_bytes_sent += payload.length;
        
        if ( session.sendMessage(
           		dest, 
           		payload, 
           		0, 
           		payload.length,
           		rpc_type != RPC_TYPE_UNREPLIABLE ? I2PSession.PROTO_DATAGRAM : I2PSession.PROTO_DATAGRAM_RAW,
                fromPort, 
                toPort, 
                opts )){
        	
        	trace( dest, "send ok" );
        	
        	return( payload.length );
        	
        }else{
        	
        	trace( dest, "send failed" );
        	
        	throw( new DHTTransportException( "sendMessage failed" ));
        }
    }
    
    @Override
    public void
    messageAvailable(
    	I2PSession 		session, 
    	int 			msg_id, 
    	long 			size, 
    	int 			proto, 
    	int 			from_port, 
    	int 			to_port )
    {
    	try{
	    	byte[] payload = session.receiveMessage(msg_id);
	        
	    	if ( payload == null ){
	    	
	    			// seen a few of these, not much we can do!
	    		
	    		return;
	    	}
	    	
	    	int raw_payload_length = payload.length;
	    	
	        if ( to_port == query_port ){
	
	        		// repliable
	
	        	I2PDatagramDissector dgDiss = new I2PDatagramDissector();
	
	        	dgDiss.loadI2PDatagram(payload);
	
	        	payload = dgDiss.getPayload();
	
	        	Destination from = dgDiss.getSender();
	
	        	receiveMessage( from, from_port, raw_payload_length, payload);
	
	        }else if ( to_port == reply_port) {
	
	        	receiveMessage( null, from_port, raw_payload_length, payload );
	        	
	        }else{
	        	
	        	if ( TRACE ) trace( "unmatched port: " + to_port + " (" + query_port + "/" + reply_port + ")");
	        }
	        
    	}catch( I2PInvalidDatagramException e ){
    		
    		// can get these on address changes it seems
    		
    	}catch( Throwable e ){
    		
    		Debug.out( e );
    	}
    }

    private void
    encodeVersion(
    	Map		map )
    {
    	map.put( "_v", "AZ" +DHTUtilsI2P.PROTOCOL_VERSION );
    }
    
    private int
    decodeVersion(
    	Map		map )
    {
        String ver_str = MapUtils.getMapString( map, "_v", null );

        if ( ver_str == null || !ver_str.startsWith( "AZ" )){
        	
        	return(  0 );
        }
                
        return( Integer.parseInt( ver_str.substring(2)));
    }
    
    private void 
    receiveMessage(
    	Destination 	from_dest, 
    	int 			from_port, 
    	int				raw_payload_length,
    	byte[]			payload ) 
    {
    	stats.total_packets_received++;
    	
    	stats.total_bytes_received += raw_payload_length;
    	
    	try{
	    	Map		map = BDecoder.decode( payload );
	
	        byte[] msg_id = (byte[])map.get( "t" );
	      
	        String type = MapUtils.getMapString( map, "y", "" );
	        
	        if ( type.equals("q")){
	        	
	        	trace( from_dest, "received" );
	        	
	            	// queries must be repliable
	        	
	        	if (( generic_flags & DHTTransportUDP.GF_DHT_SLEEPING ) == 0 || map.containsKey( "z" )){
	        		
	        		String method = MapUtils.getMapString( map, "q", "" );
	            
	        		Map args = (Map)map.get( "a" );
	            
	        		receiveQuery( msg_id, from_dest, from_port, raw_payload_length, method, args );
	        		
	        	}else{
	        		
	        		// System.out.println( "Sleeping - ignoring request" );
	        	}
	        }else if ( type.equals("r") || type.equals("e")){
	        	  
	        	Request request;
	        	
	        	synchronized( requests ){
	        		
	        		request = requests.remove( new HashWrapper( msg_id ));
	        	}
	        	
	        	if ( request != null ){
	        		
	        		trace( request.getDestination(), "received (2)" );
	        		
	        		int elapsed = (int)( SystemTime.getMonotonousTime() - request.getStartTime());
	        		
	        		if ( TRACE ) trace( "Request took " + elapsed );
	        		
	        		ReplyHandlerAdapter reply_handler = request.getHandler();
	        		
                    reply_handler.contactAlive();

	        		try{
		                if ( type.equals("r")){
		                	
		                    Map reply = (Map)map.get( "r" );
		                                        
		                    int	contact_version = decodeVersion( reply );
		                    		                    
		                    reply_handler.packetReceived( raw_payload_length );
		                    
		                    reply_handler.handleReply( contact_version, reply, elapsed );
		                    
		                }else{
		                	
	                		List error = (List)map.get("e");
		                    
	                		reply_handler.handleError( new DHTTransportException( "Received error: " + error ));
		                }
	        		}catch( Throwable e ){
	        			
	        			reply_handler.handleError( new DHTTransportException( "Reply processing failed", e ));
	        		}
	        	}else{
	        		
	        		// System.out.println( "req not found for " + map  );
	        		
	        		if ( TRACE ) trace( "Got reply to timed-out request" );
	        	}
	        }else{
	        	
	        	if ( TRACE ) trace( "Unhandled message type: " + type  );
	        }
    	}catch( Throwable e ){
    		
    		Debug.out( e );
    	}
    }
    
    private void 
    receiveQuery(
    	byte[] 			msg_id, 
    	Destination 	dest, 
    	int 			from_port, 
    	int				packet_length,
    	String 			method, 
    	Map				args )
    	
    	throws Exception
    {
    	stats.incomingRequestReceived( null, false );
    	
        if ( dest == null && !method.equals( "announce_peer")){
    
        		// only announce is valid without a replyable dest
        	
            return;
        }
        
        byte[] nid = (byte[])args.get("id");
        
        byte[] token	= null;
        
        NodeInfo node;
        
        if ( dest != null ){
        	
        	node = new NodeInfo(new NID(nid), dest, from_port);
           
        }else{
        	
            token = (byte[])args.get("token");

        	if ( token == null ){
        		
        		if ( TRACE ) trace( "Token missing, store deined" );
        		
        		return;
        	}
        	
        	synchronized( token_map ){
        		
        		node = token_map.get( new HashWrapper( token ));
        	}
        	
        	if ( node == null ){
        		
        		if ( TRACE ) trace( "Token invalid/expired, store deined" );
        		
        		return;
        	}
        }
       
        int originator_version = decodeVersion( args );
        
        DHTTransportContactI2P originator = new DHTTransportContactI2P( this, node, (byte)originator_version, 0, 0, (byte)0 );
        
        if ( method.equals("ping")){
        	
            receivePing( originator, msg_id );
      
        }else if ( method.equals("find_node")){
        	
            byte[] target = (byte[])args.get("target");
             
            receiveFindNode( originator, msg_id, target );
            
        }else if ( method.equals("get_peers")) {
        	
            byte[] hash = (byte[])args.get("info_hash");
           
            Number n_no_seed = (Number)args.get( "noseed" );
            
            boolean no_seed = n_no_seed!=null&&n_no_seed.intValue()!=0;
            
            receiveFindValue( originator, msg_id, hash, no_seed );
            
        }else if ( method.equals("announce_peer")) {
        	
            byte[] hash = (byte[])args.get("info_hash");
            
            originator.setRandomID2( token );
            
            // this is the "TCP" port, we don't care
            //int port = args.get("port").getInt();
             
            Number n_is_seed = (Number)args.get( "seed" );
            
            boolean is_seed = n_is_seed!=null&&n_is_seed.intValue()!=0;
            
            receiveStore( originator, msg_id, hash, is_seed );
            
        }else if ( method.equals("azrequest")) {

        	receiveAZRequest( originator, packet_length, msg_id, args );
        }
    }
    
    private void
    checkTimeouts()
    {
    	List<Request>	timed_out = null;
    	
    	synchronized( requests ){
    		
    		if ( requests.size() > 0 ){
    		
    			long	now = SystemTime.getMonotonousTime();
    			
    			Iterator<Request>	it = requests.values().iterator();
    			
    			while( it.hasNext()){
    				
    				Request	req = it.next();
    				
    				if ( now - req.getStartTime() > request_timeout ){
    					
    					if ( timed_out == null ){
    						
    						timed_out = new ArrayList<Request>();
    					}
    					
    					timed_out.add( req );
    					
    					it.remove();
    				}
    			}
    		}
    	}
    	
    	if ( timed_out != null ){
    		
    		stats.total_request_timeouts += timed_out.size();
    		
    		for ( Request r: timed_out ){
    			
    			try{
    				r.getHandler().handleError( new DHTTransportException( "Timeout" ));
    				
    			}catch( Throwable e ){
    				
    				Debug.out( e );
    			}
    		}
    	}
    }
    
		// direct contact-contact communication
	
	@Override
	public void
	registerTransferHandler(
		byte[]						handler_key,
		DHTTransportTransferHandler	handler )
	{
	}
	
	@Override
	public void
	registerTransferHandler(
		byte[]						handler_key,
		DHTTransportTransferHandler	handler,
		Map<String,Object>			options )
	{
		throw( new RuntimeException( "Not Supported" ));
	}
	
	@Override
	public void
	unregisterTransferHandler(
		byte[]						handler_key,
		DHTTransportTransferHandler	handler )
	{
		throw( new RuntimeException( "Not Supported" ));
	}
	
	@Override
	public byte[]
	readTransfer(
		DHTTransportProgressListener	listener,
		DHTTransportContact				target,
		byte[]							handler_key,
		byte[]							key,
		long							timeout )
	
		throws DHTTransportException
	{
		throw( new RuntimeException( "Not Supported" ));
	}
	
	@Override
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
		throw( new RuntimeException( "Not Supported" ));
	}
	
	@Override
	public byte[]
	writeReadTransfer(
		DHTTransportProgressListener	listener,
		DHTTransportContact				target,
		byte[]							handler_key,
		byte[]							data,
		long							timeout )	
	
		throws DHTTransportException
	{
		throw( new RuntimeException( "Not Supported" ));
	}

	@Override
	public boolean
	supportsStorage()
	{
		return( true );
	}
	
	@Override
	public boolean
	isReachable()
	{
		return( true );
	}
	
	@Override
	public DHTTransportContact[]
	getReachableContacts()
	{
		return( new DHTTransportContact[0]);
	}
	
	@Override
	public DHTTransportContact[]
	getRecentContacts()
	{
		return( new DHTTransportContact[0]);		
	}
	
	public DHTTransportValue
	createValue(
		DHTTransportContact		originator,
		short					flags,
		byte[]					value_bytes )
	{
		return(new DHTTransportValueImpl(  originator, flags, value_bytes ));
	}
	
	@Override
	public void
	addListener(
		DHTTransportListener	l )
	{
		
	}
	
	@Override
	public void
	removeListener(
		DHTTransportListener	l )
	{
		
	}
	
	public void
	destroy()
	{
		List<Request>	to_fail = new ArrayList<Request>();
		
		synchronized( requests ){
			
			destroyed	= true;			

			to_fail = new ArrayList<Request>( requests.values());
		}
		
		for ( Request request: to_fail ){
			
			try{
				request.handler.handleError( new DHTTransportException( "Transport destroyed" ));
				
			}catch( Throwable e ){
				
				Debug.out( e );
			}
		}
		
		timer_event.cancel();
	}
	
	private void
	trace(
		String	str )
	{
		System.out.println( str );
	}
	
	private abstract class
	ReplyHandlerAdapter
		implements ReplyHandler
	{
		private final DHTTransportContactI2P		contact;
		
		public
		ReplyHandlerAdapter(
			DHTTransportContactI2P		_contact )
		{
			contact	= _contact;
		}
		
		public void
		contactAlive()
		{
			if ( contact != null ){
				
				adapter.contactAlive( contact );
			}
		}
		
		@Override
		public void
		packetSent(
			int		length )
		{	
		}
		
		@Override
		public void
		packetReceived(
			int		length )
		{	
		}
	}
	
	private interface
	ReplyHandler
	{
		public void
		packetSent(
			int		length );
		
		public void
		packetReceived(
			int		length );
		
		public void
		handleReply(
			int						originator_version,
			Map<String,Object>		reply,
			int						elapsed );
		
		public void
		handleError(
			DHTTransportException	error );
	}
	
	private class
	Request
	{
		private Destination					dest;
		private ReplyHandlerAdapter			handler;
    	
    	private long	start_time = SystemTime.getMonotonousTime();
    	
    	private
    	Request(
    		Destination						_dest,
    		ReplyHandlerAdapter				_handler )
    	{
    		dest		= _dest;
    		handler		= _handler;
    	}
    	
    	private Destination
    	getDestination()
    	{
    		return( dest );
    	}
    	
    	private ReplyHandlerAdapter
    	getHandler()
    	{
    		return( handler );
    	}
    	
    	private long
    	getStartTime()
    	{
    		return( start_time );
    	}
	}
	
	public static class
	DHTTransportStatsI2P
		extends DHTTransportStatsImpl
	{
		private long	total_request_timeouts;
		private long	total_packets_sent;
		private long	total_packets_received;
		private long	total_bytes_sent;
		private long	total_bytes_received;

		public 
		DHTTransportStatsI2P()
		{
			super( DHTUtilsI2P.PROTOCOL_VERSION );
		}
		
		@Override
		public DHTTransportStats snapshot() {
			
			DHTTransportStatsI2P res = new DHTTransportStatsI2P();
			
			snapshotSupport( res );
			
			res.total_request_timeouts		= total_request_timeouts;
			res.total_packets_sent			= total_packets_sent;
			res.total_packets_received		= total_packets_received;
			res.total_bytes_sent			= total_bytes_sent;
			res.total_bytes_received		= total_bytes_received;
			
			return( res );
		}
		
		@Override
		public int getRouteablePercentage() {
			return( 100 );
		}
		
		@Override
		public long getRequestsTimedOut() {
			return( total_request_timeouts );
		}
		
		@Override
		public long getPacketsSent() {
			return( total_packets_sent );
		}
		
		@Override
		public long getPacketsReceived() {
			return( total_packets_received );
		}
		
		@Override
		public long getBytesSent() {
			return( total_bytes_sent );
		}
		
		@Override
		public long getBytesReceived() {
			return( total_bytes_received );
		}
	}
	
	protected static class
	DHTTransportValueImpl
		implements DHTTransportValue
	{
		private DHTTransportContact		originator;
		private short					flags;
		private byte[]					value_bytes;
		
		private final long				create_time = SystemTime.getCurrentTime();
		
		protected
		DHTTransportValueImpl(
			DHTTransportContact		_originator,
			short					_flags,
			byte[]					_value_bytes )
		{
			originator		= _originator;
			flags			= _flags;
			value_bytes		= _value_bytes;
		}
		
		@Override
		public boolean
		isLocal()
		{
			return( false );
		}
		
		@Override
		public long
		getCreationTime()
		{
			return( create_time );
		}
		
		@Override
		public byte[]
		getValue()
		{
			return( value_bytes );
		}
		
		@Override
		public int
		getVersion()
		{
			return( 0 );
		}
		
		@Override
		public DHTTransportContact
		getOriginator()
		{
			return( originator );
		}
		
		@Override
		public int
		getFlags()
		{
			return( flags&0xffff );
		}
		
		@Override
		public int
		getLifeTimeHours()
		{
			return( 0 );	// default is repub interval
		}
		
		@Override
		public byte
		getReplicationControl()
		{
			return( 0 );
		}
		
		@Override
		public byte
		getReplicationFactor() 
		{
			return( 0);
		}
		
		@Override
		public byte
		getReplicationFrequencyHours() 
		{
			return((byte)255 );
		}
		
		@Override
		public String
		getString()
		{			
			return( DHTLog.getString( value_bytes ));
		}
	};
	
	static class
	DestLookupNegativeCacheEntry
	{
		private long	first_fail;
		private int		num_fails;
		
		private
		DestLookupNegativeCacheEntry(
			long		_first_fail,
			int			_num_fails )
		{
			first_fail	= _first_fail;
			num_fails	= _num_fails;
		}
	}
}
