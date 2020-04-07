/*
 * Created on Aug 26, 2014
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
import java.util.Properties;

import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.Debug;
import com.biglybt.pif.PluginInterface;
import org.parg.azureus.plugins.networks.i2p.I2PHelperAdapter;
import org.parg.azureus.plugins.networks.i2p.router.I2PHelperRouter;
import org.parg.azureus.plugins.networks.i2p.router.I2PSMHolder;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NID;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NodeInfo;

import com.biglybt.core.dht.DHT;
import com.biglybt.core.dht.DHTFactory;
import com.biglybt.core.dht.DHTLogger;
import com.biglybt.core.dht.DHTStorageAdapter;
import com.biglybt.core.dht.control.DHTControl;
import com.biglybt.core.dht.control.DHTControlContact;
import com.biglybt.core.dht.control.impl.DHTControlContactImpl;
import com.biglybt.core.dht.db.DHTDB;
import com.biglybt.core.dht.db.DHTDBFactory;
import com.biglybt.core.dht.router.DHTRouter;
import com.biglybt.core.dht.router.DHTRouterAdapter;
import com.biglybt.core.dht.router.DHTRouterContact;
import com.biglybt.core.dht.router.DHTRouterContactAttachment;
import com.biglybt.core.dht.router.DHTRouterContactWrapper;
import com.biglybt.core.dht.router.DHTRouterObserver;
import com.biglybt.core.dht.router.DHTRouterWrapper;
import com.biglybt.core.dht.transport.DHTTransport;
import com.biglybt.core.dht.transport.DHTTransportContact;
import com.biglybt.core.dht.transport.DHTTransportFindValueReply;
import com.biglybt.core.dht.transport.DHTTransportFullStats;
import com.biglybt.core.dht.transport.DHTTransportQueryStoreReply;
import com.biglybt.core.dht.transport.DHTTransportRequestHandler;
import com.biglybt.core.dht.transport.DHTTransportStoreReply;
import com.biglybt.core.dht.transport.DHTTransportValue;
import com.biglybt.plugin.dht.DHTPluginContact;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.data.Destination;

public class 
DHTAZClient 
	extends I2PHelperAZDHT
	implements DHTLogger
{
	private AESemaphore	init_sem = new AESemaphore( "" );
	
	private I2PHelperAdapter		adapter;
	
	private volatile DHT 			dht;
	private volatile DHT 			base_dht;
	private volatile Exception		init_error;
	
	public
	DHTAZClient(
		final I2PHelperRouter.ServerInstance	inst,
		final I2PHelperAZDHT					az_dht,
		final int								dht_port,
		final I2PHelperAdapter					_adapter )
	
	{	adapter = _adapter;
	
		new AEThread2( "init" )
		{
			@Override
			public void
			run()
			{
				try{					
					while( true ){
						
						if ( inst.isDestroyed()){
							
							throw( new Exception( "Server Destroyed" ));
						}
						
						I2PSMHolder		sm_holder		= inst.getSMHolder();
						
						if ( sm_holder == null ){
							
							Thread.sleep( 1000 );
							
							continue;
						}
												
						Destination my_dest = sm_holder.getMyDestination();
						
						NID dht_nid = NodeInfo.generateNID( my_dest.calculateHash(), dht_port, inst.getRouter().getContext().random());
		
						NodeInfo my_node= new NodeInfo( dht_nid, my_dest, dht_port );
		
						DHTTransportI2P base_transport = 
							DHTTransportI2P.createTransport(
								adapter,
								new DHTI2PAdapter(){
									@Override
									public void 
									contactAlive(
										DHTTransportContactI2P contact) 
									{
									}
								},
								sm_holder, 
								my_node, 
								DHTUtilsI2P.REQUEST_TIMEOUT );
							
						//base_transport.setTraceOn( true );
						
						BogusRequestHandler base_request_handler = new BogusRequestHandler();
						
						base_transport.setRequestHandler( base_request_handler );
						
						DHTTransportAZ transport = 
							new DHTTransportAZ( 
								new DHTTransportAZ.DHTTransportAZHelper() {
									
									@Override
									public boolean isBaseContact(DHTTransportContactAZ contact) {
								
										return( false );
									}
									
									@Override
									public DHTLogger getLogger() {
										
										return( DHTAZClient.this );
									}
								}, base_transport );
		
						//transport.setTraceOn( true );
						
						BogusRequestHandler request_handler = new BogusRequestHandler();
						
						transport.setRequestHandler( request_handler );
						
						transport.setGenericFlag( DHTTransport.GF_DHT_SLEEPING, true );
						
						DHTStorageAdapter	storage_adapter = null;
												
						DHTDB database = DHTDBFactory.create( 
								storage_adapter,
								DHTControl.ORIGINAL_REPUBLISH_INTERVAL_DEFAULT,
								0,
								transport.getProtocolVersion(),
								DHTAZClient.this );
						
						database.setSleeping( true );	// don't accepts any remote storage
						
						Properties	base_props = new Properties();
		
						base_props.put( DHT.PR_ENCODE_KEYS, 0 );		// raw keys, no sha1'ing them

						base_dht = 
								DHTFactory.create( 
									base_transport, 
									new RouterWrapperBase( az_dht.getBaseDHT().getRouter(), base_transport ),
									database,
									base_props,
									storage_adapter,
									DHTAZClient.this );
						
						base_request_handler.setStats( base_dht.getControl().getStats());
						
						dht = 
							DHTFactory.create( 
								transport, 
								new RouterWrapperAZ( az_dht.getDHT().getRouter(), transport ),
								database,
								new Properties(),
								storage_adapter,
								DHTAZClient.this );
						
						request_handler.setStats( dht.getControl().getStats());

						break;
					}
				}catch( Throwable e ){
					
					Debug.out(e );
					
					if ( e instanceof Exception ){
					
						init_error	= (Exception)e;
					
					}else{
						
						init_error = new Exception( e );
					}
				}finally{
					
					init_sem.releaseForever();
				}
			}
		}.start();
	}
	
	@Override
	public boolean
	isInitialised()
	{
		return( init_sem.isReleasedForever());
	}
	
	@Override
	public boolean
	waitForInitialisation(
		long	max_millis )
	{
		return( init_sem.reserve( max_millis ));
	}
	
	@Override
	public DHT
	getDHT()
		
		throws Exception
	{
		if ( !isInitialised()){
			
			throw( new Exception( "DHT not yet initialized " ));
		}
		
		if ( init_error != null ){
			
			throw( init_error );
		}
		
		return( dht );
	}
	
	@Override
	public DHT
	getBaseDHT()
		
		throws Exception
	{
		if ( !isInitialised()){
			
			throw( new Exception( "DHT not yet initialized " ));
		}
		
		if ( init_error != null ){
			
			throw( init_error );
		}
		
		return( base_dht );
	}
	
		// DHTInterface fake methods start
	
	@Override
	public byte[]
	getID()
	{
		return( null );
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
		return( null );
	}
	
	@Override
	public DHTPluginContact[]
	getRecentContacts()
	{
		return( null );
	}
	
	@Override
	public List<DHTPluginContact>
	getClosestContacts(
		byte[]		to_id,
		boolean		live_only )
	{
		return( null );
	}
	
		// 	DHTInterface fake methods end
	
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
	
	private abstract class
	RouterWrapper
		extends DHTRouterWrapper
	{
			/*
			 * the point of this is to leverage just enough functionality from the live router
			 * to support puts/gets without any extra baggage. Unfortunately we have to map router contacts
			 * as they have an embedded transport contact which we need to 'redirect'
			 */
				
		private
		RouterWrapper(
			DHTRouter			router )
		{
			super( router );
		}
		
		protected abstract DHTRouterContact
		map(
			DHTRouterContact	contact );
		
		private List<DHTRouterContact>
		map(
			List<DHTRouterContact>	l )
		{
			if ( l == null ){
				
				return( null );
			}
			
			List<DHTRouterContact> result = new ArrayList<DHTRouterContact>( l.size());
			
			for ( DHTRouterContact c: l ){
				
				result.add( map( c ));
			}
			
			return( result );
		}
		
		@Override
		public DHTRouterContact
		getLocalContact()
		{
			return( map(getDelegate().getLocalContact()));
		}
		
		@Override
		public void
		setAdapter(
			DHTRouterAdapter	_adapter )
		{
		}

		@Override
		public void
		seed()
		{
		}
		
		@Override
		public void
		contactKnown(
			byte[]						node_id,
			DHTRouterContactAttachment	attachment,
			boolean						force )
		{
		}
		
		@Override
		public void
		contactAlive(
			byte[]						node_id,
			DHTRouterContactAttachment	attachment )
		{
		}

		@Override
		public DHTRouterContact
		contactDead(
			byte[]						node_id,
			boolean						force )
		{
			return( null );
		}
		
		@Override
		public DHTRouterContact
		findContact(
			byte[]	node_id )
		{
			return( map( getDelegate().findContact(node_id)));
		}

		@Override
		public List<DHTRouterContact>
		findClosestContacts(
			byte[]		node_id,
			int			num_to_return,
			boolean		live_only )
		{
			return( map( getDelegate().findClosestContacts(node_id, num_to_return, live_only)));
		}
		
		@Override
		public void
		recordLookup(
			byte[]	node_id )
		{
		}
		
		@Override
		public boolean
		requestPing(
			byte[]	node_id )
		{
			return( false );
		}
		
		@Override
		public void
		refreshIdleLeaves(
			long	idle_max )
		{
		}
		
		@Override
		public byte[]
		refreshRandom()
		{
			return( null );
		}
		
		@Override
		public List<DHTRouterContact>
		findBestContacts(
			int		max )
		{
			return( map( getDelegate().findBestContacts(max)));
		}
		
		@Override
		public List<DHTRouterContact>
		getAllContacts()
		{
			return( map( getDelegate().getAllContacts()));
		}
		
		@Override
		public void
		setSleeping(
			boolean	sleeping )
		{
		}
		
		@Override
		public void
		setSuspended(
			boolean			susp )
		{
		}
		
		@Override
		public void
		destroy()
		{
		}

		@Override
		public boolean addObserver(DHTRouterObserver rto)
		{
			return( false );
		}
		
		@Override
		public boolean containsObserver(DHTRouterObserver rto)
		{
			return( false );
		}

		@Override
		public boolean removeObserver(DHTRouterObserver rto)
		{
			return( false );
		}
	}
	
	private class
	RouterWrapperBase
		extends RouterWrapper
	{
			/*
			 * the point of this is to leverage just enough functionality from the live router
			 * to support puts/gets without any extra baggage. Unfortunately we have to map router contacts
			 * as they have an embedded transport contact which we need to 'redirect'
			 */
		
		private DHTTransportI2P		base_transport;
		
		private
		RouterWrapperBase(
			DHTRouter			router,
			DHTTransportI2P		transport )
		{
			super( router );
			
			base_transport = transport;
		}
		
		@Override
		protected DHTRouterContact
		map(
			DHTRouterContact	contact )
		{
			if ( contact == null ){
				
				return( null );
			}
			return( new ContactWrapper( contact ));
		}
		
		private class
		ContactWrapper
			extends DHTRouterContactWrapper
		{
			private
			ContactWrapper(
				DHTRouterContact		c )
			{
				super( c );
			}
			
			@Override
			public DHTRouterContactAttachment
			getAttachment()
			{
				DHTControlContact cc = (DHTControlContact)getDelegate().getAttachment();
				
				DHTTransportContactI2P t_c = (DHTTransportContactI2P)cc.getTransportContact();
				
				t_c = new DHTTransportContactI2P( base_transport, t_c );
				
				return( new DHTControlContactImpl( t_c, ContactWrapper.this ));	
			}
		}
	}
	
	private class
	RouterWrapperAZ
		extends RouterWrapper
	{
			/*
			 * the point of this is to leverage just enough functionality from the live router
			 * to support puts/gets without any extra baggage. Unfortunately we have to map router contacts
			 * as they have an embedded transport contact which we need to 'redirect'
			 */
		
		private DHTTransportAZ		az_transport;
		
		private
		RouterWrapperAZ(
			DHTRouter			router,
			DHTTransportAZ		transport )
		{
			super( router );
			
			az_transport = transport;
		}
		
		@Override
		protected DHTRouterContact
		map(
			DHTRouterContact	contact )
		{
			if ( contact == null ){
				
				return( null );
			}
			return( new ContactWrapper( contact ));
		}
		
		private class
		ContactWrapper
			extends DHTRouterContactWrapper
		{
			private
			ContactWrapper(
				DHTRouterContact		c )
			{
				super( c );
			}
			
			@Override
			public DHTRouterContactAttachment
			getAttachment()
			{
				DHTControlContact cc = (DHTControlContact)getDelegate().getAttachment();
				
				DHTTransportContactAZ t_c = (DHTTransportContactAZ)cc.getTransportContact();
				
				t_c = new DHTTransportContactAZ( az_transport, t_c.getBasis());
				
				return( new DHTControlContactImpl( t_c, ContactWrapper.this ));	
			}
		}
	}
	
	
	
	private class
	BogusRequestHandler
		implements DHTTransportRequestHandler
	{
		private DHTTransportFullStats		full_stats;
		
		private void
		setStats(
			DHTTransportFullStats	_stats )
		{
			full_stats = _stats;
		}
		
		@Override
		public void
		pingRequest(
			DHTTransportContact contact )
		{
			
		}
			
		@Override
		public void
		keyBlockRequest(
			DHTTransportContact contact,
			byte[]				key_block_request,
			byte[]				key_block_signature )
		{
			
		}
		
		@Override
		public DHTTransportFullStats
		statsRequest(	
			DHTTransportContact contact )
		{
			return( full_stats );
		}
		
		@Override
		public DHTTransportStoreReply
		storeRequest(
			DHTTransportContact contact, 
			byte[][]				keys,
			DHTTransportValue[][]	value_sets )
		{
			return( null );
		}
		
		@Override
		public DHTTransportQueryStoreReply
		queryStoreRequest(
			DHTTransportContact 	contact, 
			int						header_len,
			List<Object[]>			keys )
		{
			return( null );
		}
		
		@Override
		public DHTTransportContact[]
		findNodeRequest(
			DHTTransportContact contact, 
			byte[]				id )
		{
			return( null );
		}
		
		@Override
		public DHTTransportFindValueReply
		findValueRequest(
			DHTTransportContact contact, 
			byte[]				key,
			int					max_values,
			short				flags )
		{
			return( null );
		}

		@Override
		public void
		contactImported(
			DHTTransportContact		contact,
			boolean					is_bootstrap )
		{
		}
		
		@Override
		public void
		contactRemoved(
			DHTTransportContact	contact )
		{
		}
		
		@Override
		public int
		getTransportEstimatedDHTSize()
		{
			return( -1 );
		}
		
		@Override
		public void
		setTransportEstimatedDHTSize(
			int	size )
		{
		}
	}
}
