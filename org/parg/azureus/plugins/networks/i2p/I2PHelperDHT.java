/*
 * Created on Apr 17, 2014
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



package org.parg.azureus.plugins.networks.i2p;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import net.i2p.data.Destination;
import net.i2p.data.Hash;

import org.parg.azureus.plugins.networks.i2p.snarkdht.NodeInfo;
import org.parg.azureus.plugins.networks.i2p.vuzedht.I2PHelperAZDHT;

public interface 
I2PHelperDHT 
{
	public I2PHelperAZDHT
	getHelperAZDHT();
	
	public int
	getDHTIndex();
	
	public String
	getLocalAddress();
	
	public int
	getQueryPort();
	
	public int
	getReplyPort();
	
	public NodeInfo
	getNodeInfo(
		byte[]		hash );
	
	public NodeInfo
	heardAbout(
		Map			map );
	
	public void
	heardAbout(
		NodeInfo	ni );
	
	public void
	pingAll(
		boolean			az );
	
	public void
	ping(
		Destination		destination,
		int				port,
		boolean			az );
	
	public void
	ping(
		NodeInfo		node );
	
	public void
	findNode(
		Destination		destination,
		int				port,
		byte[]			id );
	
	public void
	findValue(
		Destination		destination,
		int				port,
		byte[]			id );
	
	public void
	store(
		Destination		destination,
		int				port,
		byte[]			key,
		byte[]			value );
	
	
	public boolean
	hasLocalKey(
		byte[]			hash );
			
	public void
	get(
		byte[] 						ih, 
		String						reason,
		short						flags,
		int 						max, 
		long						maxWait,
		I2PHelperDHTListener		listener );
		
	public void
	put(
		byte[] 						ih, 
		String						reason,
		short						flags,
		I2PHelperDHTListener		listener );
	
	public void
	remove(
		byte[] 						ih, 
		String						reason,
		I2PHelperDHTListener		listener );
	
	public Collection<Hash> 
	getPeersAndNoAnnounce(
		byte[] 			ih, 
		int 			max, 
		long			maxWait, 
		int 			annMax, 
		long 			annMaxWait );
		
	public Collection<Hash> 
	getPeersAndAnnounce(
		byte[] 			ih, 
		int 			max, 
		long			maxWait, 
		int 			annMax, 
		long 			annMaxWait );
	
	public void
	requestBootstrap();
	
	public void
	checkBootstrap();
	
	/**
	 * Used by the bootstrap server
	 * @param number
	 * @return
	 */
	
	public List<NodeInfo>
	getNodesForBootstrap(
		int			number );
	
	public void
	stop();
	
	public void
	print();
	
	public String
	renderStatusHTML();
	
	public String
	getStats();
	
	public String
	getStatusString();
}
