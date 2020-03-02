/*
 * Created on Jul 16, 2014
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

import com.biglybt.core.util.Debug;

import com.biglybt.core.dht.netcoords.DHTNetworkPosition;
import com.biglybt.core.dht.transport.DHTTransport;
import com.biglybt.core.dht.transport.DHTTransportContact;
import com.biglybt.core.dht.transport.DHTTransportException;
import com.biglybt.core.dht.transport.DHTTransportFullStats;
import com.biglybt.core.dht.transport.DHTTransportReplyHandler;
import com.biglybt.core.dht.transport.DHTTransportValue;

public class 
DHTTransportContactAZ
	implements DHTTransportContact
{
	private DHTTransportAZ			transport;
	private DHTTransportContactI2P	basis;	// DON'T use this for any transport operations as DHTAZClient relies on this NOT BEING DONE
	
	protected
	DHTTransportContactAZ(
		DHTTransportAZ			_transport,
		DHTTransportContactI2P	_basis )
	{
		transport		= _transport;
		basis			= _basis;
	}
	
	protected DHTTransportContactI2P
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
		transport.sendPing( handler, this );
	}
	
	public void
	sendPingForce(
		DHTTransportReplyHandler	handler )
	{
		transport.sendPing( handler, this, false );
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
		transport.sendFindValue( handler, this, key, max_values, flags );
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
		return( "AZ:" + basis.getString());
	}
}
