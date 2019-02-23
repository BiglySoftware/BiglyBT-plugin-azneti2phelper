/*
 * Created on Jul 15, 2014
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

import java.io.File;
import java.util.Properties;




import com.biglybt.core.util.ByteArrayHashMap;
import com.biglybt.core.util.Debug;
import com.biglybt.pif.PluginInterface;
import org.parg.azureus.plugins.networks.i2p.I2PHelperAdapter;

import com.biglybt.core.dht.DHT;
import com.biglybt.core.dht.DHTFactory;
import com.biglybt.core.dht.DHTLogger;
import com.biglybt.core.dht.control.DHTControlContact;
import com.biglybt.core.dht.router.DHTRouter;
import com.biglybt.core.dht.router.DHTRouterContact;
import com.biglybt.core.dht.router.DHTRouterObserver;
import com.biglybt.core.dht.transport.DHTTransportContact;
import com.biglybt.core.dht.transport.DHTTransportReplyHandlerAdapter;
import com.biglybt.core.dht.transport.DHTTransportValue;
import com.biglybt.plugin.dht.impl.DHTPluginStorageManager;

public class 
DHTAZ 
	implements DHTRouterObserver, DHTLogger
{
	private I2PHelperAdapter		adapter;
	
	private DHTI2P					base;
	private DHT						base_dht;
	private DHTTransportI2P			base_transport;
	
	private DHT 					dht;
	private DHTTransportAZ			transport;
	
	
	private ByteArrayHashMap<String>	base_contacts = new ByteArrayHashMap<String>();
	
	public
	DHTAZ(
		DHTI2P				_i2p_dht,
		I2PHelperAdapter	_adapter )
	{
		base	= _i2p_dht;
		adapter = _adapter;
		
		base_dht = base.getDHT();
		
		int	dht_index = base.getDHTIndex();
		
		File storage_dir = new File( base.getDir(), "dhtdata_az" + (dht_index==0?"":String.valueOf(dht_index)));
		
		if ( !storage_dir.isDirectory()){
		
			storage_dir.mkdirs();
		}

		DHTPluginStorageManager storage_manager = new DHTPluginStorageManager( DHTUtilsI2P.DHT_NETWORK, this, storage_dir );

		base_transport = base.getTransport();
		
		transport = 
			new DHTTransportAZ( 
				new DHTTransportAZ.DHTTransportAZHelper() {
					
					@Override
					public boolean isBaseContact(DHTTransportContactAZ contact) {
						return( DHTAZ.this.isBaseContact(contact));
					}
					
					@Override
					public DHTLogger getLogger() {
						return( base );
					}
				}, base_transport );
		
		Properties	props = new Properties();
	
		props.put( DHT.PR_ENABLE_RANDOM_LOOKUP, 	0 );		// don't need random DHT poking as we use the basis for this

		props.put( DHT.PR_CONTACTS_PER_NODE, 			10 );		// K
		props.put( DHT.PR_NODE_SPLIT_FACTOR, 			2 );		// B

		dht = DHTFactory.create( 
					transport, 
					props,
					storage_manager,
					null,
					this );
	
		
		DHT	base_dht = _i2p_dht.getDHT();
		
		base_dht.getRouter().addObserver( this );
	}
	
	public DHT
	getDHT()
	{
		return( dht );
	}
	
	protected DHT
	getBaseDHT()
	{
		return( base_dht );
	}
	
	protected void
	setSeeded()
	{
		dht.getControl().setSeeded();
	}
	
	@Override
	public void 
	removed(
		DHTRouterContact r_contact ) 
	{
		DHTTransportContactI2P t_cn = (DHTTransportContactI2P)((DHTControlContact)r_contact.getAttachment()).getTransportContact();
		
		if ( t_cn.getProtocolVersion() != DHTUtilsI2P.PROTOCOL_VERSION_NON_VUZE ){
		
			//System.out.println( "removed " + t_cn.getString());
			
			synchronized( base_contacts ){
				
				base_contacts.remove( t_cn.getID());
			}
			
			transport.contactDead( t_cn );
		}
	}
	
	@Override
	public void 
	nowFailing(
		DHTRouterContact contact ) 
	{
	}
	
	@Override
	public void 
	nowAlive(
		DHTRouterContact contact ) 
	{
	}
	
	@Override
	public void 
	locationChanged(
		DHTRouterContact contact ) 
	{
	}
	
	@Override
	public void 
	destroyed(
		DHTRouter router ) 
	{
	}
	
	@Override
	public void 
	added(
		DHTRouterContact r_contact ) 
	{
		DHTTransportContactI2P t_cn = (DHTTransportContactI2P)((DHTControlContact)r_contact.getAttachment()).getTransportContact();
		
		if ( t_cn.getProtocolVersion() != DHTUtilsI2P.PROTOCOL_VERSION_NON_VUZE ){
		
			//System.out.println( "added " + t_cn.getString());
			
			synchronized( base_contacts ){
				
				base_contacts.put( t_cn.getID(), "" );
			}

			transport.contactAlive( t_cn );
		}	
	}
	
	protected boolean
	isBaseContact(
		DHTTransportContactAZ		contact )
	{
		synchronized( base_contacts ){
			
			return( base_contacts.containsKey( contact.getID()));
		}
	}
	
	protected void
	ping(
		DHTTransportContactI2P	contact )
	{
		// for testing
		
		new DHTTransportContactAZ( transport, contact ).sendPing(
			new DHTTransportReplyHandlerAdapter() {
				
				@Override
				public void
				pingReply(
					DHTTransportContact contact )
				{
					System.out.println( "Got reply!" );
				}
				
				@Override
				public void failed(DHTTransportContact contact, Throwable error) {
					error.printStackTrace();
				}
			});
	}
	
	protected void
	findNode(
		DHTTransportContactI2P	contact,
		byte[]					node_id )
	{
		// for testing
		
		new DHTTransportContactAZ( transport, contact ).sendFindNode(
			new DHTTransportReplyHandlerAdapter() {
				
				@Override
				public void
				findNodeReply(
					DHTTransportContact 		contact,
					DHTTransportContact[]		contacts )
				{
					System.out.println( "Got reply!" );
					
					for ( DHTTransportContact c: contacts ){
						
						System.out.println( "    " + c.getString());
					}
				}
				
				@Override
				public void failed(DHTTransportContact contact, Throwable error) {
					error.printStackTrace();
				}
			},
			node_id,
			(short)0 );
	}
	
	protected void
	findValue(
		DHTTransportContactI2P	contact,
		byte[]					node_id )
	{
		// for testing
		
		new DHTTransportContactAZ( transport, contact ).sendFindValue(
			new DHTTransportReplyHandlerAdapter() {
				
				@Override
				public void
				findValueReply(
					DHTTransportContact 		contact,
					DHTTransportContact[]		contacts )
				{
					System.out.println( "Got reply!" );
					
					for ( DHTTransportContact c: contacts ){
						
						System.out.println( "    " + c.getString());
					}
				}
				
				@Override
				public void
				findValueReply(
					DHTTransportContact 	contact,
					DHTTransportValue[]		values,
					byte					diversification_type,
					boolean					more_to_come )
				{
					System.out.println( "Got reply!" );
					
					for ( DHTTransportValue value: values ){
						
						try{
							System.out.println( "    " + new String( value.getValue(), "UTF-8" ));
							
						}catch( Throwable e ){
							
						}
					}
				}
				
	
				@Override
				public void failed(DHTTransportContact contact, Throwable error) {
					error.printStackTrace();
				}
			},
			node_id,
			32,
			(short)0 );
	}
	
	protected void
	store(
		DHTTransportContactI2P	contact,
		final byte[]					key,
		final byte[]					value )
	{
		// for testing
		
		final DHTTransportContactAZ	az_contact = new DHTTransportContactAZ( transport, contact );
		
		az_contact.sendFindNode(
			new DHTTransportReplyHandlerAdapter() {
				
				@Override
				public void
				findNodeReply(
					DHTTransportContact 		contact,
					DHTTransportContact[]		contacts )
				{
					System.out.println( "Anti-spoof done, storing..." );
					
					byte[][] keys = { key };
					
					DHTTransportValue[][] values =
						{{
							transport.createValue( value )
						}};
					
					az_contact.sendStore(
						new DHTTransportReplyHandlerAdapter() {
							
							@Override
							public void
							storeReply(
								DHTTransportContact contact,
								byte[]				diversifications )
							{
								System.out.println( "Got store reply!" );
							}
							
							@Override
							public void failed(DHTTransportContact contact, Throwable error) {
								error.printStackTrace();
							}
						},
						keys,
						values,
						false );
				}
				
				@Override
				public void failed(DHTTransportContact contact, Throwable error) {
					error.printStackTrace();
				}
			},
			new byte[0],
			(short)0 );
	
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
}
