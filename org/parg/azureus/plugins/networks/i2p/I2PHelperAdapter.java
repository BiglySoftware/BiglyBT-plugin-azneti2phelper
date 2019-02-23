/*
 * Created on Mar 26, 2014
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

import java.util.List;

import com.biglybt.pif.PluginInterface;
import org.parg.azureus.plugins.networks.i2p.router.I2PHelperRouterDHT;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NodeInfo;
import org.parg.azureus.plugins.networks.i2p.vuzedht.DHTTransportContactI2P;

import net.i2p.client.streaming.I2PSocket;

public interface 
I2PHelperAdapter 
{
	public void
	log(
		String	str );
	
	public PluginInterface
	getPluginInterface();
	
	public boolean
	isDHTEnabled();
	
	public String
	getMessageText(
		String			key );
	
	public String
	getMessageText(
		String			key,
		String...		args );
	
	public void
	tryExternalBootstrap(
		I2PHelperDHT	dht,
		boolean			force );
	
	public List<NodeInfo>
	getAlternativeContacts(
		int				max );
	
	public void
	stateChanged(
		I2PHelperRouterDHT		dht,
		boolean					initialisation_complete );
	
	public void
	contactAlive(
		I2PHelperDHT			dht,
		DHTTransportContactI2P	contact );
	
	public void
	incomingConnection(
		I2PHelperRouterDHT		dht,
		I2PSocket				socket )
		
		throws Exception;
	
	public void
	outgoingConnection(
		I2PSocket		socket )
		
		throws Exception;
	
	public String
	lookup(
		String	address );
}
