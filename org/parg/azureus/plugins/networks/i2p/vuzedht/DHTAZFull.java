/*
 * Created on Aug 27, 2014
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
 */


package org.parg.azureus.plugins.networks.i2p.vuzedht;

import java.util.ArrayList;
import java.util.List;

import com.biglybt.core.dht.DHT;
import com.biglybt.core.dht.transport.DHTTransportContact;
import com.biglybt.plugin.dht.DHTPluginContact;

public class 
DHTAZFull
	extends I2PHelperAZDHT
{
	private DHTAZ		dht;
	
	public
	DHTAZFull(
		DHTAZ			_d )
	{
		dht		= _d;
	}
	
	@Override
	public boolean
	isInitialised()
	{
		return( true );
	}
	
	@Override
	public boolean
	waitForInitialisation(
		long	max_millis )
	{
		return( true );
	}
	
	public DHTAZ
	getAZDHT()
	{
		return( dht );
	}
	
	@Override
	public DHT
	getDHT()
	{
		return( dht.getDHT());
	}
	
	@Override
	public DHT
	getBaseDHT()
	{
		return( dht.getBaseDHT());
	}
	
	@Override
	public byte[]
	getID()
	{
		return( dht.getDHT().getRouter().getID());
	}
	
	@Override
	public boolean
	isIPV6()
	{
		return( false );
	}
	
	@Override
	public int
	getNetwork()
	{
		return( DHT.NW_MAIN );
	}
			
	@Override
	public DHTPluginContact[]
	getReachableContacts()
	{
		DHTTransportContact[] contacts = dht.getDHT().getTransport().getReachableContacts();
		
		DHTPluginContact[] result = new DHTPluginContact[contacts.length];
		
		for ( int i=0;i<contacts.length;i++ ){
			
			result[i] = new DHTContactImpl( contacts[i] );
		}
		
		return( result );
	}
	
	@Override
	public DHTPluginContact[]
	getRecentContacts()
	{
		DHTTransportContact[] contacts = dht.getDHT().getTransport().getRecentContacts();
		
		DHTPluginContact[] result = new DHTPluginContact[contacts.length];
		
		for ( int i=0;i<contacts.length;i++ ){
			
			result[i] = new DHTContactImpl( contacts[i] );
		}
		
		return( result );
	}
	
	@Override
	public List<DHTPluginContact>
	getClosestContacts(
		byte[]		to_id,
		boolean		live_only )
	{
		List<DHTTransportContact> contacts = dht.getDHT().getControl().getClosestKContactsList(to_id, live_only);
		
		List<DHTPluginContact> result = new ArrayList<DHTPluginContact>( contacts.size());
		
		for ( DHTTransportContact contact: contacts ){
			
			result.add( new DHTContactImpl( contact ));
		}
		
		return( result );
	}
}
