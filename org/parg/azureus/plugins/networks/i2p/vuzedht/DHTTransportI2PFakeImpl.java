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
import java.io.IOException;
import java.util.Map;

import org.parg.azureus.plugins.networks.i2p.snarkdht.NodeInfo;
import org.parg.azureus.plugins.networks.i2p.vuzedht.DHTTransportI2PRealImpl.DHTTransportStatsI2P;

import com.biglybt.core.dht.transport.DHTTransportContact;
import com.biglybt.core.dht.transport.DHTTransportException;
import com.biglybt.core.dht.transport.DHTTransportFullStats;
import com.biglybt.core.dht.transport.DHTTransportListener;
import com.biglybt.core.dht.transport.DHTTransportProgressListener;
import com.biglybt.core.dht.transport.DHTTransportReplyHandler;
import com.biglybt.core.dht.transport.DHTTransportRequestHandler;
import com.biglybt.core.dht.transport.DHTTransportStats;
import com.biglybt.core.dht.transport.DHTTransportTransferHandler;
import com.biglybt.core.dht.transport.DHTTransportValue;
import com.biglybt.core.util.RandomUtils;

import net.i2p.client.I2PSession;
import net.i2p.data.Destination;

public class 
DHTTransportI2PFakeImpl
	implements DHTTransportI2P
{
	private final DHTTransportContactI2P	local_contact;
	
	private final DHTTransportStatsI2P		stats;

	
	protected
	DHTTransportI2PFakeImpl(
		DHTI2PAdapter	_dht_adapter,
		I2PSession 		_session,
		NodeInfo		_my_node,
		int				_request_timeout )
	{	 
		stats = new DHTTransportStatsI2P(); 

		local_contact = 
				new DHTTransportContactI2P( 
						this, 
						_my_node, 
						DHTUtilsI2P.PROTOCOL_VERSION,
						RandomUtils.nextAbsoluteInt(), 0, (byte)0 );
	}
	
	@Override
	public boolean 
	isDisabled()
	{
		return( true );
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
		return( 0 );
	}
	
	@Override
	public void
	setGenericFlag(
		byte		flag,
		boolean		value )
	{
	}
	
	@Override
	public void
	setSuspended(
		boolean			susp )
	{
	}

		/**
		 * Gives access to the node ID for this transport
		 * @return
		 */

	public DHTTransportContact
	getLocalContact()
	{
		return( local_contact );
	}

	public int
	getPort()
	{
		return( 0 );
	}

	public void
	setPort(
		int	port )

		throws DHTTransportException
	{	
	}

	public long
	getTimeout()
	{
		return( 0 );
	}

	public void
	setTimeout(
		long		millis )
	{
	}

	public DHTTransportContact
	importContact(
		DataInputStream		is,
		boolean				is_bootstrap )

		throws IOException, DHTTransportException
	{
		return( null );
	}

	public void
	setRequestHandler(
		DHTTransportRequestHandler	receiver )
	{
	}

	public DHTTransportStats
	getStats()
	{
		return( stats );
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
		throw( new DHTTransportException( "" ));
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
		throw( new DHTTransportException( "" ));
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
		throw( new DHTTransportException( "" ));
	}

	public boolean
	supportsStorage()
	{
		return( false );
	}

	public boolean
	isReachable()
	{
		return( false );
	}

	public DHTTransportContact[]
	getReachableContacts()
	{
		return( new DHTTransportContact[0] );
	}

	public DHTTransportContact[]
	getRecentContacts()
	{
		return( new DHTTransportContact[0] );
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
	
	public int
	getReplyPort()
	{
		return( 0 );
	}
	
	public void
	setAZRequestHandler(
		AZRequestHandler		_azrh )
	{
	}
	
	public void
	sendAZRequest(
		AZReplyHandler				handler,
		DHTTransportContactI2P		contact,
		boolean						reply_expected,
		boolean						priority,
		Map<String, Object>			payload,
		boolean						override_sleeping )
	{
		handler.failed(contact, new DHTTransportException(""));
	}
	
	public void
	sendPing(
		Destination		dest,
		int				port )
	{
	}
	
	public boolean
	sendPing(
		NodeInfo		node )
	{
		return( false );
	}
	
	public void
	sendPing(
		DHTTransportReplyHandler	handler,
		DHTTransportContactI2P		contact )
	{
		handler.failed(contact, new DHTTransportException(""));
	}
	
	public boolean
	sendFindNode(
		NodeInfo		node,
		byte[]			target )
	{
		return( false );
	}

	public void
	sendFindNode(
		final DHTTransportReplyHandler		handler,
		final DHTTransportContactI2P		contact,
		byte[]								target,
		short								flags )
	{
		handler.failed(contact, new DHTTransportException(""));
	}
	
	public void
	sendFindValue(
		final DHTTransportReplyHandler		handler,
		final DHTTransportContactI2P		contact,
		byte[]								target,
		short								flags,
		boolean								priority )
	{
		handler.failed(contact, new DHTTransportException(""));
	}
	
	public void
	sendStore(
			final DHTTransportReplyHandler	handler,
			final DHTTransportContactI2P	contact,
			final byte[][]					keys,
			final DHTTransportValue[][]		value_sets )
	{
		handler.failed(contact, new DHTTransportException(""));
	}
	
	public boolean
	lookupDest(
		NodeInfo		node )
	{
		return( false );
	}
	
	public void
	removeContact(
		DHTTransportContactI2P	contact )
	{
	}
	
	public DHTTransportFullStats
	getFullStats(
		DHTTransportContactI2P	contact )
	{
		return( null );
	}
	
	public DHTTransportValue
	createValue(
		DHTTransportContact		originator,
		short					flags,
		byte[]					value_bytes )
	{
		return( null );
	}
	
	public void
	exportContact(
		DataOutputStream			os,
		DHTTransportContactI2P		contact )
		
		throws IOException, DHTTransportException
	{
	}
	
	public Map<String,Object>
	exportContactToMap(
		DHTTransportContactI2P		contact )
	{
		return( null );
	}
	
	public DHTTransportContactI2P
	importContact(
		NodeInfo		node,
		boolean			is_bootstrap )
	{
		return( null );
	}
	
	public DHTTransportContactI2P
	importContact(
		byte[]		hash,
		int			port,
		byte[]		id,
		int			version )
	{
		return( null );
	}
	
	public DHTTransportContactI2P
	importContactFromMap(
		Map<String,Object>		map )
	{
		return( null );
	}
	
	public void
	destroy()
	{
		
	}
}
