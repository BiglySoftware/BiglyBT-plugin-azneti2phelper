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
import java.util.Map;

import org.parg.azureus.plugins.networks.i2p.I2PHelperAdapter;
import org.parg.azureus.plugins.networks.i2p.router.I2PSMHolder;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NodeInfo;

import com.biglybt.core.dht.transport.DHTTransport;
import com.biglybt.core.dht.transport.DHTTransportContact;
import com.biglybt.core.dht.transport.DHTTransportException;
import com.biglybt.core.dht.transport.DHTTransportFullStats;
import com.biglybt.core.dht.transport.DHTTransportReplyHandler;
import com.biglybt.core.dht.transport.DHTTransportValue;

import net.i2p.data.Destination;

public interface
DHTTransportI2P
	extends DHTTransport
{	
	public static DHTTransportI2P
	createTransport(
		I2PHelperAdapter	helper_adapter,
		DHTI2PAdapter		dht_adapter,
		I2PSMHolder			sm_holder,
		NodeInfo			my_node,
		int					request_timeout )
	{
		if ( helper_adapter.isDHTEnabled()){
		
			return( new DHTTransportI2PRealImpl( dht_adapter, sm_holder, my_node, request_timeout ));
			
		}else{
			
			return( new DHTTransportI2PFakeImpl( dht_adapter, sm_holder, my_node, request_timeout ));
		}
	}
	
	public boolean
	isDisabled();
	
	public int
	getReplyPort();
	
	public void
	setAZRequestHandler(
		AZRequestHandler		_azrh );
	
	public void
	sendAZRequest(
		AZReplyHandler				handler,
		DHTTransportContactI2P		contact,
		boolean						reply_expected,
		boolean						priority,
		Map<String, Object>			payload,
		boolean						override_sleeping );
	
	public void
	sendPing(
		Destination		dest,
		int				port );
	
	public boolean
	sendPing(
		NodeInfo		node );
	
	public void
	sendPing(
		DHTTransportReplyHandler	handler,
		DHTTransportContactI2P		contact );
	
	public boolean
	sendFindNode(
		NodeInfo		node,
		byte[]			target );

	public void
	sendFindNode(
		final DHTTransportReplyHandler		handler,
		final DHTTransportContactI2P		contact,
		byte[]								target,
		short								flags );
	
	public void
	sendFindValue(
		final DHTTransportReplyHandler		handler,
		final DHTTransportContactI2P		contact,
		byte[]								target,
		short								flags,
		boolean								priority );
	
	public void
	sendStore(
			final DHTTransportReplyHandler	handler,
			final DHTTransportContactI2P	contact,
			final byte[][]					keys,
			final DHTTransportValue[][]		value_sets );
	
	public boolean
	lookupDest(
		NodeInfo		node );
	
	public void
	removeContact(
		DHTTransportContactI2P	contact );
	
	public DHTTransportFullStats
	getFullStats(
		DHTTransportContactI2P	contact );
	
	public DHTTransportValue
	createValue(
		DHTTransportContact		originator,
		short					flags,
		byte[]					value_bytes );
	
	public void
	exportContact(
		DataOutputStream			os,
		DHTTransportContactI2P		contact )
		
		throws IOException, DHTTransportException;
	
	public Map<String,Object>
	exportContactToMap(
		DHTTransportContactI2P		contact );
	

	public DHTTransportContactI2P
	importContact(
		NodeInfo		node,
		boolean			is_bootstrap );
	
	public DHTTransportContactI2P
	importContact(
		byte[]		hash,
		int			port,
		byte[]		id,
		int			version );
	
	public DHTTransportContactI2P
	importContactFromMap(
		Map<String,Object>		map );
	
	public void
	destroy();
	
	public interface
	AZRequestHandler
	{
		public void
		packetSent(
			int		length );
		
		public void
		packetReceived(
			int		length );
		
		public AZRequestResult
		receiveRequest(
			DHTTransportContactI2P		contact,
			Map<String,Object>			args )
			
			throws Exception;
	}
	
	public interface
	AZRequestResult
	{
		public Map<String,Object>
		getReply();
		
		public boolean
		isAdHoc();
	}
	
	public interface
	AZReplyHandler
	{
		public void
		packetSent(
			int		length );
		
		public void
		packetReceived(
			int		length );
		
		public void
		reply(
			DHTTransportContactI2P		contact,
			Map							map,
			int							elapsed );
		
		public void
		failed(
			DHTTransportContactI2P		contact,
			DHTTransportException		error );
	}
	
}
