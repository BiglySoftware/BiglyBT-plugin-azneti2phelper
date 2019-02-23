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

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;

import net.i2p.data.Base32;

import com.biglybt.core.util.ByteFormatter;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SystemTime;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NodeInfo;
import org.parg.azureus.plugins.networks.i2p.vuzedht.DHTTransportI2P.AZRequestResult;

import com.biglybt.core.dht.DHT;
import com.biglybt.core.dht.DHTLogger;
import com.biglybt.core.dht.impl.DHTLog;
import com.biglybt.core.dht.transport.DHTTransport;
import com.biglybt.core.dht.transport.DHTTransportContact;
import com.biglybt.core.dht.transport.DHTTransportException;
import com.biglybt.core.dht.transport.DHTTransportFindValueReply;
import com.biglybt.core.dht.transport.DHTTransportFullStats;
import com.biglybt.core.dht.transport.DHTTransportListener;
import com.biglybt.core.dht.transport.DHTTransportProgressListener;
import com.biglybt.core.dht.transport.DHTTransportReplyHandler;
import com.biglybt.core.dht.transport.DHTTransportRequestHandler;
import com.biglybt.core.dht.transport.DHTTransportStats;
import com.biglybt.core.dht.transport.DHTTransportStoreReply;
import com.biglybt.core.dht.transport.DHTTransportTransferHandler;
import com.biglybt.core.dht.transport.DHTTransportValue;
import com.biglybt.core.dht.transport.util.DHTTransferHandler;
import com.biglybt.core.dht.transport.util.DHTTransportRequestCounter;
import com.biglybt.core.dht.transport.util.DHTTransportStatsImpl;
import com.biglybt.core.dht.transport.util.DHTTransferHandler.Packet;

public class 
DHTTransportAZ
	implements DHTTransport, DHTTransportI2P.AZRequestHandler
{
	private boolean TRACE = false;

	private static final int	METHOD_PING			= 0;
	private static final int	METHOD_FIND_NODE	= 1;
	private static final int	METHOD_FIND_VALUE	= 2;
	private static final int	METHOD_STORE		= 3;
	private static final int	METHOD_DATA			= 4;
	
	private static final int	MAX_DATA_SIZE	= 10*1024;
	
		// skew our time randomly so that multiple transports don't show the same clock times in requests
	
	private final int	TIME_OFFSET = RandomUtils.SECURE_RANDOM.nextInt( 4*60*1000 ) - 2*60*1000;
	
	private DHTTransportAZHelper		helper;
	
	private DHTTransportI2P				base_transport;
	
	private DHTTransportStatsI2P		stats;
	
	private DHTTransportContactAZ		local_contact;
	
	private DHTTransportRequestHandler	request_handler;
	
	private DHTTransferHandler 			xfer_handler;

	private static final int CONTACT_HISTORY_MAX 		= 32;
	
	private Map<InetSocketAddress,DHTTransportContact>	contact_history = 
		new LinkedHashMap<InetSocketAddress,DHTTransportContact>(CONTACT_HISTORY_MAX,0.75f,true)
		{
			@Override
			protected boolean
			removeEldestEntry(
		   		Map.Entry<InetSocketAddress,DHTTransportContact> eldest) 
			{
				return size() > CONTACT_HISTORY_MAX;
			}
		};
		
	private volatile boolean			destroyed;
	
	protected
	DHTTransportAZ(
		DHTTransportAZHelper	_helper,
		DHTTransportI2P			_base_transport )
	{
		helper			= _helper;
		base_transport	= _base_transport;
		
		stats = new DHTTransportStatsI2P();
				
		local_contact = 
			new DHTTransportContactAZ( this, (DHTTransportContactI2P)base_transport.getLocalContact());
		
		xfer_handler = 
				new DHTTransferHandler(
					new DHTTransferHandler.Adapter() {
						
						@Override
						public void
						sendRequest(
							DHTTransportContact 	contact, 
							Packet 					packet	) 
						{									
							DHTTransportAZ.this.sendData( (DHTTransportContactAZ)contact, packet );
						}
						
						@Override
						public long
						getConnectionID() 
						{
							return( RandomUtils.SECURE_RANDOM.nextLong());
						}
					}, 
					MAX_DATA_SIZE, 
					helper.getLogger());
		
		base_transport.setAZRequestHandler( this );
	}
	
	protected void
	setTraceOn(
		boolean		b )
	{
		TRACE = b;
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
		return( base_transport.getGenericFlags());
	}
	
	@Override
	public void
	setGenericFlag(
		byte		flag,
		boolean		value )
	{
		base_transport.setGenericFlag(flag, value);
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
		return( base_transport.getPort());
	}
	
	@Override
	public void
	setPort(
		int	port )
	
		throws DHTTransportException
	{
		base_transport.setPort( port );
	}
	
	@Override
	public long
	getTimeout()
	{
		return( base_transport.getTimeout());
	}
	
	@Override
	public void
	setTimeout(
		long		millis )
	{
		base_transport.setTimeout( millis );
	}
	
	protected DHTTransportFullStats
	getFullStats(
		DHTTransportContactAZ	contact )
	{
		if ( contact == local_contact ){
			
			return( request_handler.statsRequest( contact ));
		}
		
		Debug.out( "Status not supported for remote contacts" );
		
		return( null );
	}
	
	protected void
	contactAlive(
		DHTTransportContactI2P		i2p_contact )
	{
		request_handler.contactImported( new DHTTransportContactAZ( this, i2p_contact ), false );
	}
	
	protected void
	contactDead(
		DHTTransportContactI2P		i2p_contact )
	{
		request_handler.contactRemoved( new DHTTransportContactAZ( this, i2p_contact ));
	}
	
	@Override
	public DHTTransportContact
	importContact(
		DataInputStream		is,
		boolean				is_bootstrap )
	
		throws IOException, DHTTransportException
	{
		throw( new DHTTransportException( "Not supported" ));
	}
	
	protected void
	removeContact(
		DHTTransportContactAZ	contact )
	{
		request_handler.contactRemoved( contact );
	}
	
	@Override
	public void
	setRequestHandler(
		DHTTransportRequestHandler	_request_handler )
	{
		if ( request_handler == null ){
		
			request_handler	= new DHTTransportRequestCounter( _request_handler, stats );
		}
	}
	
	@Override
	public DHTTransportStatsI2P
	getStats()
	{
		return( stats );
	}
	
	public void
	sendPing(
		final DHTTransportReplyHandler		handler,
		final DHTTransportContactAZ			contact )
	{		
		boolean known = helper.isBaseContact( contact );
		
		if ( known ){
			
			handler.pingReply( contact, -1 );
			
		}else{
			
			if ( contact.getProtocolVersion() < DHTUtilsI2P.PROTOCOL_VERSION_AZ_MSGS ){
				
				handler.failed( contact, new Exception( "Contact doesn't support az messaging" ));
				
				return;
			}

			stats.pingSent( null );
			
			Map<String,Object>	payload = new HashMap<String, Object>();
									
			sendRequest(
				new AZReplyHandlerAdapter()
				{	
					@Override
					public void
					reply(
						DHTTransportContactI2P 		basis,
						Map							map,
						int							elapsed )
					{
						stats.pingOK();
						
						handler.pingReply( contact, elapsed );
					}
					
					@Override
					public void 
					failed(
						DHTTransportContactI2P 		basis, 
						DHTTransportException 		error) 
					{
						stats.pingFailed();
						
						handler.failed( contact, error );
					}
				},
				contact,
				METHOD_PING,
				true,
				false,
				payload );
			
			if ( TRACE ) trace( "AZ: sendPing to " + contact.getString());
		}
	}
	
	private Map<String,Object>
	receivePing(
		DHTTransportContactAZ		contact,
		Map<String,Object>			payload )
	{
		request_handler.pingRequest( contact );
		
		return( new HashMap<String,Object>());
	}
		
	public void
	sendFindNode(
		final DHTTransportReplyHandler		handler,
		final DHTTransportContactAZ			contact,
		byte[]								target,
		short								flags )
	{
		boolean priority = (flags&I2PHelperAZDHT.FLAG_HIGH_PRIORITY) != 0;
		
		flags &= ~I2PHelperAZDHT.FLAG_HIGH_PRIORITY;
		
		if ( TRACE ) trace( "AZ: sendFindNode for " + ByteFormatter.encodeString( target ) + " to " + contact.getString());
		
		if ( contact.getProtocolVersion() < DHTUtilsI2P.PROTOCOL_VERSION_AZ_MSGS ){
			
			handler.failed( contact, new Exception( "Contact doesn't support az messaging" ));
			
			return;
		}
		
		stats.findNodeSent( null );
		
		Map<String,Object>	payload = new HashMap<String, Object>();
		
		payload.put("h", target );
		
			// flag not used 
		
		sendRequest(
			new AZReplyHandlerAdapter()
			{	
				@Override
				public void
				reply(
					DHTTransportContactI2P 		basis,
					Map							map,
					int							elapsed )
				{
					if ( TRACE ) trace( "AZ: sendFindNode to " + contact.getString() + " OK" );

					stats.findNodeOK();
					
					List<Map<String,Object>>	l_contacts = (List<Map<String,Object>>)map.get("c");
					
					DHTTransportContact[] contacts = decodeContacts( l_contacts );
					
					handler.findNodeReply(
						contact,
						contacts );
				}
				
				@Override
				public void 
				failed(
					DHTTransportContactI2P 		basis, 
					DHTTransportException 		error) 
				{
					if ( TRACE ) trace( "AZ: sendFindNode to " + contact.getString() + " failed" );

					stats.findNodeFailed();
					
					handler.failed( contact, error );
				}
			},
			contact,
			METHOD_FIND_NODE,
			true,
			priority,
			payload );
	}
    
	private Map<String,Object>
	receiveFindNode(
		DHTTransportContactAZ		contact,
		Map<String,Object>			payload )
	{
		byte[]	hash = (byte[])payload.get( "h" );
		
		DHTTransportContact[] contacts = request_handler.findNodeRequest( contact, hash );
		
		List<Map<String,Object>>	l_contacts = encodeContacts( contacts );
		
		Map<String,Object> reply = new HashMap<String,Object>();
		
		reply.put( "c", l_contacts );
		
		return( reply );
	}
	
	public void
	sendFindValue(
		final DHTTransportReplyHandler		handler,
		final DHTTransportContactAZ			contact,
		byte[]								target,
		int									max_values,
		short								flags )
	{
		boolean priority = (flags&I2PHelperAZDHT.FLAG_HIGH_PRIORITY) != 0;
		
		flags &= ~I2PHelperAZDHT.FLAG_HIGH_PRIORITY;
		
		if ( TRACE ) trace( "AZ: sendFindValue for " + ByteFormatter.encodeString( target ) + "/" + max_values + "/" + flags + " to " + contact.getString());
		
		if ( contact.getProtocolVersion() < DHTUtilsI2P.PROTOCOL_VERSION_AZ_MSGS ){
			
			handler.failed( contact, new Exception( "Contact doesn't support az messaging" ));
			
			return;
		}
		
		stats.findValueSent( null );
		
		Map<String,Object>	payload = new HashMap<String, Object>();
		
		payload.put("h", target );
		payload.put("m", max_values );
		payload.put("f", new Long(flags));
				
		sendRequest(
			new AZReplyHandlerAdapter()
			{	
				@Override
				public void
				reply(
					DHTTransportContactI2P 		basis,
					Map							map,
					int							elapsed )
				{
					stats.findValueOK();
					
					if ( map.containsKey( "b" )){
						
						if ( TRACE ) trace( "AZ: sendFindValue to " + contact.getString() + " OK - keyblock reply" );

						byte[]	key = (byte[])map.get("k");
						byte[]	sig = (byte[])map.get("s");
						
						handler.keyBlockRequest( contact, key, sig );
						
						handler.failed( contact, new Throwable( "key blocked" ));
						
					}else if ( map.containsKey( "c" )){
					
						if ( TRACE ) trace( "AZ: sendFindValue to " + contact.getString() + " OK - contacts reply" );

						List<Map<String,Object>>	l_contacts = (List<Map<String,Object>>)map.get("c");
					
						DHTTransportContact[] contacts = decodeContacts( l_contacts );
					
						handler.findValueReply(
							contact,
							contacts );
						
					}else{
						
						if ( TRACE ) trace( "AZ: sendFindValue to " + contact.getString() + " OK - values reply" );

						List<Map<String,Object>>	l_values = (List<Map<String,Object>>)map.get("v");
						
						DHTTransportValue[] values = decodeValues( l_values, 0 );
						
						byte div = ((Number)map.get( "d")).byteValue();
						
						handler.findValueReply(
							contact,
							values,
							div,
							false );
					}
				}
				
				@Override
				public void 
				failed(
					DHTTransportContactI2P 		basis, 
					DHTTransportException 		error) 
				{
					if ( TRACE ) trace( "AZ: sendFindValue to " + contact.getString() + " failed" );

					stats.findValueFailed();
					
					handler.failed( contact, error );
				}
			},
			contact,
			METHOD_FIND_VALUE,
			true,
			priority,
			payload );
    }

	private Map<String,Object>
	receiveFindValue(
		DHTTransportContactAZ		contact,
		Map<String,Object>			payload )
	{
		byte[]	hash 		= (byte[])payload.get( "h" );
		int		max_values 	= ((Number)payload.get("m" )).intValue();
		short	flags		= ((Number)payload.get("f" )).shortValue();
		
		DHTTransportFindValueReply fv_reply = request_handler.findValueRequest( contact, hash, max_values, flags );
		
		Map<String,Object> reply = new HashMap<String,Object>();

		if ( fv_reply.blocked()){
			
			reply.put( "b", 1 );
			reply.put( "k", fv_reply.getBlockedKey());
			reply.put( "s", fv_reply.getBlockedSignature());
			
		}else if ( fv_reply.hit()){
			
			List<Map<String,Object>>	l_values = encodeValues( fv_reply.getValues(), -contact.getClockSkew());
			
			reply.put( "v", l_values );
			reply.put( "d", (long)(fv_reply.getDiversificationType()&0xff));
			
		}else{
			
			List<Map<String,Object>>	l_contacts = encodeContacts( fv_reply.getContacts());
			
			reply.put( "c", l_contacts );
		}
		
		return( reply );
	}
	
	
	protected void
	sendStore(
		final DHTTransportReplyHandler	handler,
		final DHTTransportContactAZ		contact,
		byte[][]						keys,
		DHTTransportValue[][]			value_sets )
	{
		if ( TRACE ){
			String keys_str = "";
			
			for ( byte[] key: keys ){
				
				keys_str += (keys_str.length()==0?"":",") + ByteFormatter.encodeString( key );
			}
			
			trace( "AZ: sendStore for " + keys_str + " to " + contact.getString());
		}

		if ( contact.getProtocolVersion() < DHTUtilsI2P.PROTOCOL_VERSION_AZ_MSGS ){
			
			handler.failed( contact, new Exception( "Contact doesn't support az messaging" ));
			
			return;
		}
		
		stats.storeSent( null );
		
		Map<String,Object>	payload = new HashMap<String, Object>();
		
		List<byte[]>	keys_l = new ArrayList<byte[]>( keys.length );
		
		for ( byte[] key: keys ){
			
			keys_l.add( key );
		}
		
		payload.put( "k", keys_l );
		
		List<List<Map<String,Object>>> values_l = new ArrayList<List<Map<String,Object>>>( value_sets.length );
		
		for ( DHTTransportValue[] values: value_sets ){
			
			values_l.add( encodeValues( values, 0 ));	// skew is 0 when sending store as we are the originator
		}
		
		payload.put( "v", values_l );
		
		sendRequest(
			new AZReplyHandlerAdapter()
			{	
				@Override
				public void
				reply(
					DHTTransportContactI2P 		basis,
					Map							map,
					int							elapsed )
				{
					if ( TRACE ) trace( "AZ: sendStore to " + contact.getString() + " OK" );

					stats.storeOK();
					
					if ( map.containsKey( "b" )){
						
						byte[]	key = (byte[])map.get("k");
						byte[]	sig = (byte[])map.get("s");
						
						handler.keyBlockRequest( contact, key, sig );
						
						handler.failed( contact, new Throwable( "key blocked" ));
						
					}else{
						
						byte[]	divs =	(byte[])map.get( "d" );
					
						handler.storeReply( contact, divs );
					}
				}
				
				@Override
				public void 
				failed(
					DHTTransportContactI2P 		basis, 
					DHTTransportException 		error) 
				{
					if ( TRACE ) trace( "AZ: sendStore to " + contact.getString() + " failed" );

					stats.storeFailed();
					
					handler.failed( contact, error );
				}
			},
			contact,
			METHOD_STORE,
			true,
			false,	// destination should be resolved already when storing so whatever 
			payload );
	}
	
	private Map<String,Object>
	receiveStore(
		DHTTransportContactAZ		contact,
		Map<String,Object>			payload )
	{
		List<byte[]> keys_l = (List<byte[]>)payload.get( "k" );
		
		byte[][] keys = new byte[keys_l.size()][];
		
		for ( int i=0;i<keys.length;i++){
			
			keys[i] = keys_l.get(i);
		}
		
		List<List<Map<String,Object>>> values_l =  (List<List<Map<String,Object>>>)payload.get( "v" );
		
		DHTTransportValue[][]	value_sets = new DHTTransportValue[values_l.size()][];
		
		for ( int i=0;i<value_sets.length;i++){
			
			value_sets[i] = decodeValues( values_l.get(i), contact.getClockSkew());
		}

		DHTTransportStoreReply store_reply = request_handler.storeRequest(contact, keys, value_sets);
				
		Map<String,Object> reply = new HashMap<String,Object>();
		
		if ( store_reply.blocked()){
			
			reply.put( "b", 1 );
			reply.put( "k", store_reply.getBlockRequest());
			reply.put( "s", store_reply.getBlockSignature());

		}else{
			
			reply.put( "d", store_reply.getDiversificationTypes());
		}
		
		return( reply );
	}

	protected void
	sendData(
		final DHTTransportContactAZ		contact,
		Packet							packet )
	{
		stats.dataSent( null );
		
		Map<String,Object>	payload = new HashMap<String, Object>();
				
		byte[]	data 	= packet.getData();
		
		int	start_pos 	= packet.getStartPosition();
		int length		= packet.getLength();
		
		if( data.length > 0 ){
			
			if ( start_pos != 0 || length != data.length ){
				
				byte[] temp = new byte[length];
				
				System.arraycopy( data, start_pos, temp, 0, length );
				
				data	= temp;
			}
		}
		
		payload.put( "c", packet.getConnectionId());
		payload.put( "p", (int)packet.getPacketType());
		payload.put( "z", packet.getTransferKey());
		payload.put( "r", packet.getRequestKey());
		payload.put( "d", data);
		payload.put( "s", start_pos);
		payload.put( "l", length );
		payload.put( "t", packet.getTotalLength());
		
		// System.out.println( "Sending " + payload );
		
		DHTTransportAZ.this.sendRequest(
			new AZReplyHandlerAdapter()
			{	
				@Override
				public void 
				packetSent(
					int length) 
				{
					super.packetSent( length );
					
						// no reply expected so treat this as a successful send
					
					if ( TRACE ) trace( "AZ: sendData to " + contact.getString() + " OK" );
					
					stats.dataOK();
				}
				
				@Override
				public void
				reply(
					DHTTransportContactI2P 		basis,
					Map							map,
					int							elapsed )
				{
					// see above
				}
				
				@Override
				public void 
				failed(
					DHTTransportContactI2P 		basis, 
					DHTTransportException 		error) 
				{
					if ( TRACE ) trace( "AZ: sendData to " + contact.getString() + " failed" );
	
					stats.dataFailed();							
				}
			},
			contact,
			METHOD_DATA,
			false,		// no immediate reply expected
			true,
			payload );
	}
	
	private Map<String,Object>
	receiveData(
		DHTTransportContactAZ		contact,
		Map<String,Object>			payload )
	{
		stats.dataReceived();

		// System.out.println( "Received " + payload );
		
		if ( TRACE ) trace( "AZ: receiveData from " + contact.getString());

		long 	connection_id 	= ((Number)payload.get("c" )).longValue();
		byte 	packet_type 	= ((Number)payload.get("p" )).byteValue();
		byte[]	transfer_key	= (byte[])payload.get("z" );
		byte[]	request_key		= (byte[])payload.get("r" );
		byte[]	data			= (byte[])payload.get("d" );
		int 	start_position 	= ((Number)payload.get("s" )).intValue();
		int 	length 			= ((Number)payload.get("l" )).intValue();
		int 	total_length 	= ((Number)payload.get("t" )).intValue();

		Packet	packet =
			new Packet(
				connection_id,
				packet_type,
				transfer_key,
				request_key,
				data,
				start_position,
				length,
				total_length );
		
		xfer_handler.receivePacket( contact, packet );
		
		return( null );
	}
	
		// --------------
	
	private List<Map<String,Object>>
	encodeContacts(
		DHTTransportContact[]		contacts )
	{
		List<Map<String,Object>>	result = new ArrayList<Map<String,Object>>( contacts.length );
		
		for ( DHTTransportContact c: contacts ){
			
			result.add( encodeContact( c ));
		}
		
		return( result );
	}
	
	private DHTTransportContact[]
	decodeContacts(
		List<Map<String,Object>>		l_contacts )
	{
		List<DHTTransportContact> contacts = new ArrayList<DHTTransportContact>( l_contacts.size());
		
		for ( Map<String,Object>	c: l_contacts ){
			
			try{
				contacts.add( decodeContact( c ));
				
			}catch( Throwable e ){
				
			}
		}
		
		return( contacts.toArray( new DHTTransportContact[contacts.size()]));
	}
	
	private Map<String,Object>
	encodeContact(
		DHTTransportContact		c )
	{
		Map<String,Object>	result = new HashMap<String, Object>();

		if ( c instanceof DHTTransportContactAZ ){
			
			DHTTransportContactAZ	contact = (DHTTransportContactAZ)c;
			
			NodeInfo node_info = contact.getBasis().getNode();
						
			result.put( "v", new Long(c.getProtocolVersion()&0xff));
			result.put( "h", node_info.getHash().getData());
			result.put( "p", node_info.getPort());
			result.put( "i", node_info.getNID().getData());
			
		}else{
			
				// encoding an 'anonymous contact' for an 'anonymous' value
			
			byte[]	hash 	= new byte[32];
			int		p		= 1;
			
				// generate a nid that will at least verify as one (see NodeInfo)
				// note that destination resolution shortcut-fails for this
			
			byte[] 	n = new byte[20];
	       
	        n[4] ^= (byte) (p >> 8);
	        n[5] ^= (byte) p;
	        
			result.put( "v", 0 );
			result.put( "h", hash );
			result.put( "p",p );
			result.put( "i", n );
		}
		
		return( result );
	}
	
	private DHTTransportContact
	decodeContact(
		Map<String,Object>	c )
	{
		int		ver		= ((Number)c.get( "v" )).intValue();
		byte[]	hash 	= (byte[])c.get( "h" );
		int		port	= ((Number)c.get( "p" )).intValue();
		byte[]	id	 	= (byte[])c.get( "i" );
		
		DHTTransportContactI2P base_contact = base_transport.importContact( hash, port, id, ver );
		
		return( new DHTTransportContactAZ( this, base_contact ));
	}
	
	private List<Map<String,Object>>
	encodeValues(
		DHTTransportValue[]		values,
		long					skew )
	{
		List<Map<String,Object>>	result = new ArrayList<Map<String,Object>>( values.length );
		
		for ( DHTTransportValue v: values ){
			
			result.add( encodeValue( v, skew ));
		}
		
		return( result );
	}
	
	private DHTTransportValue[]
	decodeValues(
		List<Map<String,Object>>	values_l,
		long						skew )
	{
		DHTTransportValue[]	result = new DHTTransportValue[ values_l.size()];
		
		for ( int i=0;i<result.length;i++){
			
			result[i] = decodeValue(values_l.get(i), skew );
		}
		
		return( result );
	}
			
	private Map<String,Object>
	encodeValue(
		DHTTransportValue		value,
		long					skew )
	{
		 Map<String,Object>	result = new HashMap<String, Object>();
		 
		 result.put( "v", value.getVersion());
		 result.put( "t", value.getCreationTime() + skew );
		 result.put( "w", value.getValue());
		 
		 result.put( "o", encodeContact( value.getOriginator()));
		 
		 result.put( "f", value.getFlags());
		 result.put( "l", value.getLifeTimeHours());
		 
		 return( result );
	}
	
	private DHTTransportValue
	decodeValue(
		Map<String,Object>		map,
		long					skew )
	{
		final int	version		= ((Number)map.get("v")).intValue();

		long create_time = (Long)map.get( "t" );
		
		final long 	created		= create_time + skew;

		final byte[]	value_bytes	= (byte[])map.get( "w" );
		
		final DHTTransportContact	originator = decodeContact((Map<String,Object>)map.get("o"));
		
		final int	flags		= ((Number)map.get( "f" )).intValue();
		final int 	life_hours 	= ((Number)map.get( "l" )).intValue();

		
		DHTTransportValue value = 
			new DHTTransportValue()
			{
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
					return( created );
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
					return( version );
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
					return( flags );
				}
				
				@Override
				public int
				getLifeTimeHours()
				{
					return( life_hours );
				}
				
				@Override
				public byte
				getReplicationControl()
				{
					return( DHT.REP_FACT_DEFAULT );
				}
				
				@Override
				public byte
				getReplicationFactor() 
				{
					return( DHT.REP_FACT_DEFAULT );
				}
				
				@Override
				public byte
				getReplicationFrequencyHours() 
				{
					return( DHT.REP_FACT_DEFAULT);
				}
				
				@Override
				public String
				getString()
				{
					long	now = SystemTime.getCurrentTime();
					
					return( DHTLog.getString( value_bytes ) + " - " + new String(value_bytes) + "{v=" + version + ",f=" + 
							Integer.toHexString(flags) + ",l=" + life_hours + ",r=" + Integer.toHexString(getReplicationControl()) + ",ca=" + (now - created ) + ",or=" + originator.getString() +"}" );
				}
			};
			
		return( value );
	}
	
		// --------------
	
	private void
	sendRequest(
		final DHTTransportI2P.AZReplyHandler	reply_handler,
		final DHTTransportContactAZ				contact,
		int										method,
		boolean									reply_expected,
		boolean									priority,
		Map<String,Object>						payload )
	{
		payload.put( "_m", method );

		payload.put( "_i", getLocalContact().getInstanceID());
		payload.put( "_t", SystemTime.getCurrentTime() + TIME_OFFSET );
		payload.put( "_f", (int)getGenericFlags());
		
		if ( TRACE ) System.out.println( "AZRequest to " + contact.getString() + ": " + payload );

		base_transport.sendAZRequest(
			new DHTTransportI2P.AZReplyHandler()
			{
				@Override
				public void 
				packetSent(int length) 
				{
					reply_handler.packetSent(length);
				} 
				
				@Override
				public void 
				packetReceived(int length) 
				{
					reply_handler.packetReceived(length);
				}
				
				@Override
				public void
				reply(
					DHTTransportContactI2P		contact,
					Map							payload,
					int							elapsed )
				{
					if ( TRACE ) System.out.println( "AZReply from " + contact.getString() + ": " + payload );
					
					int		instance_id	= ((Number)payload.get( "_i" )).intValue();
					int		flags		= ((Number)payload.get( "_f" )).intValue();
					
					contact.setDetails( instance_id, (byte)flags);
					
					reply_handler.reply( contact, payload, elapsed );
					
					contactAlive( contact );
				}
				
				@Override
				public void
				failed(
					DHTTransportContactI2P		contact,
					DHTTransportException		error )
				{
					reply_handler.failed(contact, error);
				}
			},
			contact.getBasis(),
			reply_expected,
			priority,
			payload,
			method == METHOD_DATA );	// allow data requests/replies to/from sleeping nodes to support msg-sync
	}
	
	@Override
	public void 
	packetSent(
		int length) 
	{
		stats.total_packets_sent++;
		stats.total_bytes_sent += length;
	}

	@Override
	public void 
	packetReceived(
		int length) 
	{
		stats.total_packets_received++;
		stats.total_bytes_received += length;
	}
	
	@Override
	public AZRequestResult
	receiveRequest(
		DHTTransportContactI2P		contact,
		Map<String,Object>			payload_in )
		
		throws Exception
	{
		if ( request_handler == null ){
			
			throw( new Exception( "Not ready to process requests" ));
		}
		
		int		method		= ((Number)payload_in.get( "_m" )).intValue();

		int		instance_id	= ((Number)payload_in.get( "_i" )).intValue();
		long	time		= ((Number)payload_in.get( "_t" )).longValue();
		int		flags		= ((Number)payload_in.get( "_f" )).intValue();
		
		long skew = SystemTime.getCurrentTime() - time;
		
		stats.recordSkew( contact.getAddress(), skew );
		
		contact.setDetails( instance_id, skew, (byte)flags );
		
		Map<String,Object>		payload_out = new HashMap<String, Object>();
		
		if ( TRACE ) trace( "Received request: " + payload_in );
		
		DHTTransportContactAZ az_contact = new DHTTransportContactAZ( this, contact );
		
		contactAlive( az_contact );
		
		boolean	adhoc = true;
		
		switch( method ){
			case METHOD_PING:{
				payload_out = receivePing( az_contact, payload_in );
				break;
			}
			case METHOD_FIND_NODE:{
				payload_out = receiveFindNode( az_contact, payload_in );
				break;
			}
			case METHOD_STORE:{
				payload_out = receiveStore( az_contact, payload_in );
				break;
			}
			case METHOD_FIND_VALUE:{
				payload_out = receiveFindValue( az_contact, payload_in );
				break;
			}
			case METHOD_DATA:{
				payload_out = receiveData( az_contact, payload_in );
				adhoc = false;
				break;
			}

			default:{
					
				throw( new Exception( "Unknown method: " + method ));
			}
		}
		
		if ( payload_out != null ){
			
			payload_out.put( "_i", getLocalContact().getInstanceID());
			payload_out.put( "_f", (int)getGenericFlags());
		}
		
		return( new AZRequestResultImpl(payload_out, adhoc));
	}

	private final class
	AZRequestResultImpl
		implements AZRequestResult
	{
		private final Map<String,Object>		payload;
		private final boolean					adhoc;
		
		private
		AZRequestResultImpl(
			 Map<String,Object>		_payload,
			 boolean				_adhoc )
		{
			payload		= _payload;
			adhoc		= _adhoc;
		}
		
		@Override
		public Map<String,Object>
		getReply()
		{
			return( payload );
		}
		
		@Override
		public boolean
		isAdHoc()
		{
			return( adhoc );
		}
	}
	
	public DHTTransportContactAZ
	importContact(
		InetSocketAddress		address )
	{
			// NID is encoded into the address - <nid>.<dest>
		
		byte version = DHTUtilsI2P.PROTOCOL_VERSION;
		
		String host_name = address.getHostName();
		
		String[] bits = host_name.split( "\\." );
		
		byte[]	id = Base32.decode( bits[0] );
		
		byte[]	hash	= Base32.decode( bits[1] );
		
		return( new DHTTransportContactAZ( this, base_transport.importContact( hash, address.getPort(), id, version )));
	}
    
	public DHTTransportContactAZ
	importContact(
		Map<String,Object>		map )
	{
			// NID is encoded into the address - <nid>.<dest>
		
		DHTTransportContactI2P contact = base_transport.importContactFromMap( map );
		
		if ( contact == null ){
			
			return( null );
		}
		
		return( new DHTTransportContactAZ( this, contact ));
	}
	
		// direct contact-contact communication
	
	@Override
	public void
	registerTransferHandler(
		byte[]						handler_key,
		DHTTransportTransferHandler	handler )
	{
		xfer_handler.registerTransferHandler( handler_key, handler );
	}
	
	@Override
	public void
	registerTransferHandler(
		byte[]						handler_key,
		DHTTransportTransferHandler	handler,
		Map<String,Object>			options )
	{
		xfer_handler.registerTransferHandler( handler_key, handler, options);
	}
	
	@Override
	public void
	unregisterTransferHandler(
		byte[]						handler_key,
		DHTTransportTransferHandler	handler )
	{
		xfer_handler.unregisterTransferHandler(handler_key, handler);
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
		return( xfer_handler.readTransfer( listener, target, handler_key, key, timeout ));
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
		xfer_handler.writeTransfer(listener, target, handler_key, key, data, timeout);
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
		return( xfer_handler.writeReadTransfer( listener, target, handler_key, data, timeout ));
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
	
	private void
	contactAlive(
		DHTTransportContact	contact )
	{
		synchronized( contact_history ){
		
			contact_history.put( contact.getTransportAddress(), contact );
		}
	}
	
	@Override
	public DHTTransportContact[]
	getReachableContacts()
	{
		return( getRecentContacts());
	}
	
	@Override
	public DHTTransportContact[]
	getRecentContacts()
	{
		synchronized( contact_history ){

			Collection<DHTTransportContact> vals = contact_history.values();
			
			DHTTransportContact[]	res = new DHTTransportContact[vals.size()];
			
			vals.toArray( res );
			
			return( res );
		}
	}
	
	protected DHTTransportValue
	createValue(
		byte[] value )
	{
		return( base_transport.createValue( getLocalContact(), (short)0, value  ));
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
	
	protected void
	destroy()
	{
		synchronized( this ){
			
			destroyed	= true;			

		}
	}
	
	private void
	trace(
		String	str )
	{
		System.out.println( str );
	}
	
	private abstract class
	AZReplyHandlerAdapter
		implements DHTTransportI2P.AZReplyHandler
	{
		@Override
		public void 
		packetSent(
			int length) 
		{
			stats.total_packets_sent++;
			stats.total_bytes_sent += length;
		}
		
		@Override
		public void 
		packetReceived(
			int length) 
		{
			stats.total_packets_received++;
			stats.total_bytes_received += length;
		}
	}
	
	private class
	DHTTransportStatsI2P
		extends DHTTransportStatsImpl
	{
		private long	total_request_timeouts;
		private long	total_packets_sent;
		private long	total_packets_received;
		private long	total_bytes_sent;
		private long	total_bytes_received;

		private 
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
	
	public interface
	DHTTransportAZHelper
	{
		public boolean
		isBaseContact(
			DHTTransportContactAZ		contact );
		
		public DHTLogger
		getLogger();
	}
}
