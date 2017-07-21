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

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import net.i2p.data.Base32;

import com.biglybt.core.util.ByteFormatter;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.SystemTime;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NodeInfo;

import com.biglybt.core.dht.netcoords.DHTNetworkPosition;
import com.biglybt.core.dht.transport.DHTTransport;
import com.biglybt.core.dht.transport.DHTTransportContact;
import com.biglybt.core.dht.transport.DHTTransportException;
import com.biglybt.core.dht.transport.DHTTransportFullStats;
import com.biglybt.core.dht.transport.DHTTransportReplyHandler;
import com.biglybt.core.dht.transport.DHTTransportValue;
import com.biglybt.core.dht.transport.udp.DHTTransportUDP;

public class 
DHTTransportContactI2P 
	implements DHTTransportContact
{
	protected static final byte[]	DEFAULT_TOKEN = {};
	
	private final DHTTransportI2P		transport;
	private final NodeInfo				node;
	private byte						version;
	private int							instance_id;
	private long						skew;
	private byte						generic_flags;
	
	private InetSocketAddress	address;
	
	private byte[]				id;
	
	
	private byte[]				random_id	= DEFAULT_TOKEN;
	private long				random_id_set_time;				
	
	protected
	DHTTransportContactI2P(
		DHTTransportI2P		_transport,
		NodeInfo			_node,
		byte				_version,
		int					_instance_id,
		long				_skew,
		byte				_generic_flags )
	{
		transport 		= _transport;
		node			= _node;
		version			= _version;
		instance_id		= _instance_id;
		skew			= _skew;
		generic_flags	= _generic_flags;
		
		String 	host = Base32.encode( node.getHash().getData())  + ".b32.i2p";
		
		address = InetSocketAddress.createUnresolved( host, node.getPort());

		id		= node.getNID().getData();
	}
	
	protected
	DHTTransportContactI2P(
		DHTTransportI2P			_transport,
		DHTTransportContactI2P	_other )
	{
		transport 		= _transport;
		
		node			= _other.node;
		version			= _other.version;
		instance_id		= _other.instance_id;
		skew			= _other.skew;
		generic_flags	= _other.generic_flags;
		address 		= _other.address;
		id				= _other.id;
	}
	
	protected void
	setDetails(
		int			_instance_id,
		byte		_flags )
	{
		instance_id		= _instance_id;
		generic_flags	= _flags;
	}
	
	protected void
	setDetails(
		int			_instance_id,
		long		_skew,
		byte		_flags )
	{
		instance_id		= _instance_id;
		skew			= _skew;
		generic_flags	= _flags;
	}
	
	public NodeInfo
	getNode()
	{
		return( node );
	}
	
	protected void
	setProtocolVersion(
		byte		_version )
	{
		version	= _version;
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
		return( instance_id );
	}
	
	@Override
	public byte[]
	getID()
	{
		return( id );
	}
	
	@Override
	public byte
	getProtocolVersion()
	{
		return( version );
	}
	
	@Override
	public long
	getClockSkew()
	{
		return( skew );
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
		random_id = id;
		
		random_id_set_time = SystemTime.getMonotonousTime();
	}
	
	protected long
	getRandomID2Age()
	{
		if ( random_id_set_time == 0  ){
			
			return( -1 );
		}
		
		return( SystemTime.getMonotonousTime() - random_id_set_time );
	}
	
	@Override
	public byte[]
	getRandomID2()
	{
		return( random_id );
	}
	
	@Override
	public String
	getName()
	{
		return( address.toString());
	}
	
	@Override
	public byte[]
	getBloomKey()
	{
		return( node.getHash().getData());
	}
	
	@Override
	public InetSocketAddress
	getAddress()
	{
		return( address );
	}
	
	@Override
	public InetSocketAddress
	getTransportAddress()
	{
		return( address );
	}
	
	@Override
	public InetSocketAddress
	getExternalAddress()
	{
		return( address );
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
		return(( generic_flags & DHTTransportUDP.GF_DHT_SLEEPING ) != 0 );
	}
	
	@Override
	public void
	sendPing(
		DHTTransportReplyHandler	handler )
	{
		transport.sendPing( handler, this );
	}
	
	@Override
	public void
	sendImmediatePing(
		DHTTransportReplyHandler	handler,
		long						timeout )
	{
		Debug.out( "Not Supported" );
		
		handler.failed( this, new Exception( "Not Supported" ));
	}

	@Override
	public void
	sendStats(
		DHTTransportReplyHandler	handler )
	{
		Debug.out( "Not Supported" );
		
		handler.failed( this, new Exception( "Not Supported" ));
	}
	
	@Override
	public void
	sendStore(
		DHTTransportReplyHandler	handler,
		byte[][]					keys,
		DHTTransportValue[][]		value_sets,
		boolean						immediate )
	{
		transport.sendStore( handler, this, keys, value_sets );
	}
	
	@Override
	public void
	sendQueryStore(
		DHTTransportReplyHandler	handler,
		int							header_length,
		List<Object[]>				key_details )
	{
		Debug.out( "Not Supported" );
		
		handler.failed( this, new Exception( "Not Supported" ));
	}
	
	@Override
	public void
	sendFindNode(
		DHTTransportReplyHandler	handler,
		byte[]						id,
		short						flags )
	{		
		transport.sendFindNode( handler, this, id, flags );
	}
		
	@Override
	public void
	sendFindValue(
		DHTTransportReplyHandler	handler,
		byte[]						key,
		int							max_values,
		short						flags )
	{
		boolean priority = (flags&I2PHelperAZDHT.FLAG_HIGH_PRIORITY) != 0;

		transport.sendFindValue( handler, this, key, flags, priority );
	}
		
	@Override
	public void
	sendKeyBlock(
		DHTTransportReplyHandler	handler,
		byte[]						key_block_request,
		byte[]						key_block_signature )
	{
		Debug.out( "Not Supported" );
		
		handler.failed( this, new Exception( "Not Supported" ));	
	}

	@Override
	public DHTTransportFullStats
	getStats()
	{
		return( transport.getFullStats( this ));
	}
	
	@Override
	public void
	exportContact(
		DataOutputStream	os )
	
		throws IOException, DHTTransportException
	{
		transport.exportContact( os, this );
	}
	
	@Override
	public Map<String, Object>
	exportContactToMap()
	{
		return( transport.exportContactToMap( this ));
	}
	
	@Override
	public void
	remove()
	{
		transport.removeContact( this );
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
		return( getName() + ",nid=" + ByteFormatter.encodeString( node.getNID().getData()) + ",v=" + version);
	}
}
