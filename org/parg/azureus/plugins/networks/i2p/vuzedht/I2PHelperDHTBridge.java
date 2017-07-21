/*
 * Created on May 16, 2016
 * Created by Paul Gardner
 * 
 * Copyright 2016 Azureus Software, Inc.  All rights reserved.
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

import java.util.*;

import com.biglybt.core.util.AENetworkClassifier;
import com.biglybt.core.util.AERunnable;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.AsyncDispatcher;
import com.biglybt.core.util.BDecoder;
import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.ByteFormatter;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.pif.ddb.DistributedDatabase;
import com.biglybt.pif.ddb.DistributedDatabaseContact;
import com.biglybt.pif.ddb.DistributedDatabaseEvent;
import com.biglybt.pif.ddb.DistributedDatabaseException;
import com.biglybt.pif.ddb.DistributedDatabaseKey;
import com.biglybt.pif.ddb.DistributedDatabaseListener;
import com.biglybt.pif.ddb.DistributedDatabaseTransferHandler;
import com.biglybt.pif.ddb.DistributedDatabaseTransferType;
import com.biglybt.pif.ddb.DistributedDatabaseValue;
import org.parg.azureus.plugins.networks.i2p.I2PHelperPlugin;
import org.parg.azureus.plugins.networks.i2p.router.I2PHelperRouter;

import com.biglybt.core.dht.control.DHTControl;
import com.biglybt.plugin.dht.DHTPluginOperationListener;

public class 
I2PHelperDHTBridge 
	implements DistributedDatabaseTransferType, DistributedDatabaseTransferHandler
{
	private static boolean TEST_LOOPBACK	= false;
	
	static{
		if ( TEST_LOOPBACK ){
			System.err.println( "**** I2P DHT Bridge loopback enabled ****" );
		}
	}
	
	private I2PHelperPlugin			plugin;
	
	private final AESemaphore		init_sem = new AESemaphore( "I2PHelperDHTBridge" );
			
	private boolean					ddb_write_initialised;
	
	private DistributedDatabase			ddb_read;
	private DistributedDatabase[]		ddb_write = new DistributedDatabase[2];
	
	private AsyncDispatcher		bridge_dispatcher = new AsyncDispatcher();
	
	private List<BridgeWrite>	bridge_writes = new LinkedList<BridgeWrite>();
	
	public
	I2PHelperDHTBridge(
		I2PHelperPlugin		_plugin )
	{
		plugin	= _plugin;
	}
	
	public void
	setDDB(
		DistributedDatabase _read_ddb )
	{
		try{

			ddb_read = _read_ddb;
		
			ddb_read.addTransferHandler( this, this );
	
		}catch( Throwable e ){
			
			Debug.out( e );
			
		}finally{
			
			init_sem.releaseForever();
		}
	}
	
	public void
	writeToBridge(
		String						desc,
		byte[]						key,
		byte[]						value,
		DHTPluginOperationListener	listener )
	{
		log( "Bridge Write starts for '" + desc + "': " + ByteFormatter.encodeString( key ));
		
		BridgeWrite	bw = new BridgeWrite( desc, key, value, listener );

		synchronized( bridge_writes ){
						
			bridge_writes.add( bw );
			
			if ( bridge_writes.size() == 1 ){
				
				SimpleTimer.addPeriodicEvent(
					"I2PBridge:repub",
					5*60*1000,
					new TimerEventPerformer() {
						
						@Override
						public void 
						perform(
							TimerEvent event) 
						{
							long	now = SystemTime.getMonotonousTime();
							
							synchronized( bridge_writes ){
								
								for ( BridgeWrite bw: bridge_writes ){
									
									if ( now - bw.getLastWrite() >= DHTControl.ORIGINAL_REPUBLISH_INTERVAL_DEFAULT ){
										
										bw.setLastWrite( now );
										
										writeToBridge( bw, false );
									}
								}
							}
						}
					});
			}
		}
		
		writeToBridge( bw, true );
	}
	
	public void
	writeToBridge(
		final BridgeWrite		bridge_write,
		final boolean			first_time )
	{
		if ( bridge_dispatcher.getQueueSize() > 256 ){
			
			if ( first_time ){
				
				bridge_write.started();
				
				bridge_write.completed();
			}
			
			return;
		}
		
		bridge_dispatcher.dispatch(
			new AERunnable(){
				@Override
				public void
				runSupport()
				{
					writeToBridgeAsync( bridge_write, first_time );
				}
			});
	}
	
	private void
	writeToBridgeAsync(
		BridgeWrite		bridge_write,
		boolean			first_time )
	{
		init_sem.reserve();
		
		if ( first_time ){
			bridge_write.started();
		}
		
		try{		
			synchronized( this ){
				
				if ( !ddb_write_initialised ){
					
					ddb_write_initialised = true;
			
					Map<String,Object>	options = new HashMap<String, Object>();
					
					options.put( "server_id", "DHT Bridge" );
					options.put( "server_id_transient", true );
					options.put( "server_sm_type", I2PHelperRouter.SM_TYPE_PURE );
							
					for ( int i=0;i<2;i++){
						
						String[]	nets = i==0?new String[]{ AENetworkClassifier.AT_PUBLIC, AENetworkClassifier.AT_I2P }:new String[]{ AENetworkClassifier.AT_I2P };
						
						options.put( "server_name", i==0?"Mix Bridge":"I2P Bridge" );
		
						List<DistributedDatabase> ddbs = plugin.getPluginInterface().getUtilities().getDistributedDatabases( nets, options );
						
						for ( DistributedDatabase ddb: ddbs ){
							
							if ( ddb.getNetwork() == AENetworkClassifier.AT_I2P ){
								
								while( !ddb.isInitialized()){
									
									Thread.sleep(1000);
								}
																
								ddb_write[i] = ddb;
							}
						}
					}
					
					log( "Bridge init complete" );

				}
			}
			
			writeToBridgeSupport( bridge_write );
			
		}catch( Throwable e ){
		
			log( "Bridge init failed", e );
			
			Debug.out( e );
			
		}finally{
			
			if ( first_time ){
				bridge_write.completed();
			}
		}
	}
	
	private void
	writeToBridgeSupport(
		BridgeWrite	bridge_write )
	{
		final String		desc 	= bridge_write.getDesc();
		final byte[]		key		= bridge_write.getKey();
		final byte[]		value	= bridge_write.getValue();
		
		if ( TEST_LOOPBACK ){
			
				// have to store it in ddb_read as the actual write will go elsewhere in the mix DHT
			
			try{
				System.out.println( "Writing to mix, key=" + ByteFormatter.encodeString( key ));
				
				DistributedDatabaseKey 		k = ddb_read.createKey( key, desc );
				DistributedDatabaseValue 	v = ddb_read.createValue( value );
		
				ddb_read.write(
						new DistributedDatabaseListener()
						{
							@Override
							public void
							event(
								DistributedDatabaseEvent		event )
							{
							}
						},
						k, v );
				
			}catch( Throwable e ){
				
				Debug.out( e );
			}
			
		}
		
		DistributedDatabase			pure_dht 	= ddb_write[1];

		if ( pure_dht != null ){
			
			try{		
				DistributedDatabaseKey 		pure_k = pure_dht.createKey( key, desc );
				DistributedDatabaseValue 	pure_v = pure_dht.createValue( value );
		
				pure_k.setFlags( DistributedDatabaseKey.FL_ANON );
			
				pure_dht.write(
						new DistributedDatabaseListener()
						{
							@Override
							public void
							event(
								DistributedDatabaseEvent		event )
							{
							}
						},
						pure_k, pure_v );
				
			}catch( Throwable e ){
				
				Debug.out( e );
			}
		}
		
		final DistributedDatabase	mix_dht 	= ddb_write[0];

		if ( mix_dht != null ){
			
			try{
	
				DistributedDatabaseKey 		mix_k = mix_dht.createKey( key, desc );
				DistributedDatabaseValue 	mix_v = mix_dht.createValue( value );
		
				mix_k.setFlags( DistributedDatabaseKey.FL_ANON );

				mix_dht.write(
						new DistributedDatabaseListener()
						{
							private List<DistributedDatabaseContact>	write_contacts = new ArrayList<DistributedDatabaseContact>();
							@Override
							public void
							event(
								DistributedDatabaseEvent		event )
							{
								int type = event.getType();
							
								if ( type == DistributedDatabaseEvent.ET_VALUE_WRITTEN ){
									
									synchronized( write_contacts ){
									
										write_contacts.add(event.getContact());
									}
								}else if ( type == DistributedDatabaseEvent.ET_OPERATION_COMPLETE ){
									
									log( "Bridge write complete for '" + desc + "'" );
									
									bridge_dispatcher.dispatch(
										new AERunnable(){
											@Override
											public void
											runSupport()
											{
												sendBridgeRequest( mix_dht, desc, key, value,  write_contacts );
											}
										});
								}
							}
						},
						mix_k, mix_v );
				
			}catch( Throwable e ){
				
				log( "Bridge write failed for '" + desc + "'", e );
				
				Debug.out( e );
			}
		}
	}
	
	private void
	sendBridgeRequest(
		DistributedDatabase					ddb,
		String								desc,
		byte[]								key,
		byte[]								value,
		List<DistributedDatabaseContact>	contacts )
	{
		Map<String,Object>	request = new HashMap<String,Object>();
		
		request.put( "k", key );
		request.put( "v", value );
	
		if ( TEST_LOOPBACK ){
		
			contacts.add( 0, ddb_read.getLocalContact());
		}
		
		try{
			DistributedDatabaseKey read_key = ddb.createKey( BEncoder.encode( request ));
	
			boolean	done = false;
			
			int	bad_version = 0;
			
			for ( DistributedDatabaseContact contact: contacts ){
				
				if ( contact.getVersion() < DHTUtilsI2P.PROTOCOL_VERSION_BRIDGE ){
					
					bad_version++;
					
					continue;
				}
								
				try{
					DistributedDatabaseValue result = 
						contact.read(
							null,
							this,
							read_key,
							30*1000 );
										
					if ( result != null ){
						
						Map<String,Object> reply = (Map<String,Object>)BDecoder.decode((byte[])result.getValue( byte[].class ));

						Long r = (Long)reply.get( "a" );
						
						if ( r != null && r == 1 ){
						
							done = true;
							
							log( "Bridge replication complete for '" + desc + "'" );
							
							break;
						}
					}
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
			
			if ( !done ){
				
				log( "Bridge replication failed for '" + desc + "', no relay: contacts=" + contacts.size() + ", bv=" + bad_version );
			}
		}catch( Throwable e ){
			
			log( "Bridge replication failed for '" + desc + "'", e );
			
			Debug.out( e );
		}
	}
		
	@Override
	public DistributedDatabaseValue
	write(
		DistributedDatabaseContact 			contact,
		DistributedDatabaseTransferType 	type, 
		DistributedDatabaseKey 				key,
		DistributedDatabaseValue 			value ) 
					
		throws DistributedDatabaseException 
	{
		return( null );
	}
	
	@Override
	public DistributedDatabaseValue 
	read(
		DistributedDatabaseContact 			contact,
		DistributedDatabaseTransferType 	type, 
		DistributedDatabaseKey 				ddb_key )
		
		throws DistributedDatabaseException 
	{
		Object	o_key = ddb_key.getKey();
		
		try{
			byte[]	key_bytes = (byte[])o_key;
						
			Map<String,Object>	request = BDecoder.decode( key_bytes );
			
			byte[]	request_key		= (byte[])request.get( "k" );
			byte[]	request_value	= (byte[])request.get( "v" );
			
			List<DistributedDatabaseValue> values = ddb_read.getValues( ddb_read.createKey( request_key));
						
			boolean	ok = false;
			
			for ( DistributedDatabaseValue v: values ){
				
				byte[] b_value = (byte[])v.getValue( byte[].class );
				
				if ( Arrays.equals( b_value, request_value )){
										
					ok = true;
							
					break;
				}
			}
			
			if ( ok ){
				
				DistributedDatabase public_ddb = plugin.getPluginInterface().getDistributedDatabase();
				
				if ( public_ddb.isAvailable()){
					
					DistributedDatabaseKey dd_key = public_ddb.createKey( request_key, "Bridge mapping" );
					
					dd_key.setFlags( DistributedDatabaseKey.FL_ANON | DistributedDatabaseKey.FL_BRIDGED );
					
					DistributedDatabaseValue dd_value = public_ddb.createValue( request_value );
					
					public_ddb.write(
						new DistributedDatabaseListener() {							
							@Override
							public void event(DistributedDatabaseEvent event) {
							}
						}, dd_key, dd_value );
				}else{
					
					ok = false;
				}
			}
			
			Map<String,Object>	result = new HashMap<String, Object>();
			
			result.put( "a", new Long(ok?1:0));
			
			return( ddb_read.createValue( BEncoder.encode( result )));
			
		}catch( Throwable e ){
			
			return( null );
		}
	}
	
	private void
	log(
		String	str )
	{
		if ( TEST_LOOPBACK ){
			System.out.println( str );
		}
		
		plugin.log( str );
	}
	
	private void
	log(
		String		str,
		Throwable 	e )
	{
		if ( TEST_LOOPBACK ){
			System.out.println( str );
			e.printStackTrace();
		}
		
		plugin.log( str, e );
	}
	
	private static class
	BridgeWrite
	{
		private final String						desc;
		private final byte[]						key;
		private final byte[]						value;
		private final DHTPluginOperationListener	listener;
		
		private long		last_write;
		
		private
		BridgeWrite(
			String						_desc,
			byte[]						_key,
			byte[]						_value,
			DHTPluginOperationListener	_listener )
		{
			desc		= _desc;
			key			= _key;
			value		= _value;
			listener	= _listener;
			
			last_write = SystemTime.getMonotonousTime();
		}
		
		private String
		getDesc()
		{
			return( desc );
		}
		
		private byte[]
		getKey()
		{
			return( key );
		}
		
		private byte[]
		getValue()
		{
			return( value );
		}
		
		private long
		getLastWrite()
		{
			return( last_write );
		}
		
		private void
		setLastWrite(
			long		t )
		{
			last_write	= t;
		}
		
		private void
		started()
		{
			try{
				if ( listener != null ){
					listener.starts(key);
				}
			}catch( Throwable e ){
				Debug.out(e);
			}
		}
		
		private void
		completed()
		{
			try{
				if ( listener != null ){
					listener.complete( key, false );
				}
			}catch( Throwable e ){
				Debug.out(e);
			}
		}
	}
}
