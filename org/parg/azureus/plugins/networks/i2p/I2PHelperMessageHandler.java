/*
 * Created on May 8, 2014
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

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.*;

import net.i2p.data.Base32;

import com.biglybt.core.util.AENetworkClassifier;
import com.biglybt.core.util.BDecoder;
import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.DirectByteBuffer;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SHA1Simple;

import com.biglybt.core.networkmanager.*;
import com.biglybt.core.networkmanager.impl.tcp.TransportEndpointTCP;
import com.biglybt.core.peermanager.messaging.Message;
import com.biglybt.core.peermanager.messaging.MessageException;
import com.biglybt.core.peermanager.messaging.MessageManager;
import com.biglybt.core.peermanager.messaging.MessagingUtil;
import com.biglybt.core.peermanager.messaging.azureus.AZStylePeerExchange;
import com.biglybt.core.peermanager.messaging.bittorrent.BTHandshake;
import com.biglybt.core.peermanager.messaging.bittorrent.BTMessageFactory;
import com.biglybt.core.peermanager.messaging.bittorrent.ltep.LTHandshake;
import com.biglybt.core.peermanager.messaging.bittorrent.ltep.LTMessage;
import com.biglybt.core.peermanager.messaging.bittorrent.ltep.LTMessageDecoder;
import com.biglybt.core.peermanager.messaging.bittorrent.ltep.LTMessageEncoder;
import com.biglybt.core.peermanager.peerdb.PeerExchangerItem;
import com.biglybt.core.peermanager.peerdb.PeerItem;
import com.biglybt.core.peermanager.peerdb.PeerItemFactory;


public class 
I2PHelperMessageHandler
	implements NetworkConnectionFactory.NetworkConnectionFactoryListener
{
	private I2PHelperPlugin		plugin;
	
	private byte[]	peer_id_mask = new byte[12];
	
	{
		RandomUtils.nextSecureBytes( peer_id_mask );
	}
	
	private LTI2PDHT	i2p_dht	= new LTI2PDHT();
	private LTI2PPEX	i2p_pex	= new LTI2PPEX();

	private LTMessage[]	lt_messages = { i2p_dht, i2p_pex };
	
	private Set<String>	lt_message_ids = new HashSet<String>();
	
	{
		for ( LTMessage message: lt_messages ){
			
			lt_message_ids.add( message.getID());
		}
	}
	
	private volatile long	data_bytes_sent;
	private volatile long	data_bytes_received;
	
	protected
	I2PHelperMessageHandler(
		I2PHelperPlugin		_plugin )
	{
		plugin	= _plugin;
		
		for ( LTMessage message: lt_messages ){
		
			registerMessage( message );
		}
		
		NetworkConnectionFactory.addListener( this );			
	}
	
	public long[]
	getDataTotals()
	{
		return( new long[]{ data_bytes_sent, data_bytes_received });
	}
	
	private void
	registerMessage(
		Message	message )
	{
		try{
			unregisterMessage( message );
			
			LTMessageDecoder.addDefaultExtensionHandler( message.getFeatureSubID(), message.getIDBytes());
			
			MessageManager.getSingleton().registerMessageType( message );
			
		}catch( Throwable e ){
			
			Debug.out( e );
		}
	}
	
	private void
	unregisterMessage(
		Message	message )
	{
		Message existing = MessageManager.getSingleton().lookupMessage( message.getIDBytes());
		
		if ( existing != null ){
			
			MessageManager.getSingleton().deregisterMessageType( existing );
		}
		
		LTMessageDecoder.removeDefaultExtensionHandler( message.getFeatureSubID() );
	}
	
	@Override
	public void
	connectionCreated(
		final NetworkConnection		connection )
	{
		final InetSocketAddress address = connection.getEndpoint().getNotionalAddress();
		
		if ( address.isUnresolved()){
		
			final String peer_host = address.getHostName();

			if ( peer_host.endsWith( ".i2p" )){
				
				final OutgoingMessageQueue out_queue = connection.getOutgoingMessageQueue();

				connection.getOutgoingMessageQueue().registerQueueListener(
					new OutgoingMessageQueue.MessageQueueListener() 
					{						
						@Override
						public void protocolBytesSent(int byte_count) {
						}
						
						@Override
						public void messageSent(Message message) {
						}
						
						@Override
						public void messageRemoved(Message message) {
						}
						
						@Override
						public void messageQueued(Message message) {
						}
						
						@Override
						public boolean messageAdded(Message message) {
														
							if ( message instanceof BTHandshake ){
							
								BTHandshake	bt_handshake = (BTHandshake)message;
								
									// disable az messaging so we don't need to implements parallel az message support
									// for i2p_dht and i2p_pex
								
								bt_handshake.getReserved()[5] &= ~3;

									// deterministically switch peer-id (factor in dht network in case we switch nets)
								
								byte[] peer_id = bt_handshake.getPeerId();
								
								peer_id = peer_id.clone();
								
								for ( int i=0;i<peer_id_mask.length;i++){
									
									peer_id[i+8] ^= peer_id_mask[i];
								}
								
								peer_id[17]++;	// i2p specific 

								byte	dht_index = (byte)plugin.selectDHTIndex( bt_handshake.getDataHash());
																
								peer_id[19] += dht_index;
								
								byte[] sha1 = new SHA1Simple().calculateHash( peer_id );
								
								for ( int i=8;i<20;i++){
									
									peer_id[i] = sha1[i];
								}
								
								bt_handshake.setPeerId( peer_id );
								
							}else if ( message instanceof LTHandshake ){
								
								LTHandshake lt_handshake = (LTHandshake)message;
								
								Map data_map = lt_handshake.getDataMap();
								
								data_map.put( "p", new Long( 6881 ));
								
								data_map.remove( "ipv6" );	// shouldn't be set but whatever...
								
								lt_handshake.addOptionalExtensionMapping( LTI2PDHT.MSG_ID, LTI2PDHT.SUBID_I2P_DHT );
								
								lt_handshake.addOptionalExtensionMapping( LTI2PPEX.MSG_ID, LTI2PPEX.SUBID_I2P_PEX );
							}
							
							return true;
						}
						
						@Override
						public void flush() {
						}
						
						@Override
						public void 
						dataBytesSent(
							int byte_count) 
						{
							data_bytes_sent += byte_count;
						}
					});
				
				connection.getIncomingMessageQueue().registerQueueListener(
					new IncomingMessageQueue.MessageQueueListener() {
						
						private byte[] torrent_hash;
						
						@Override
						public void protocolBytesReceived(int byte_count) {
						}
						
						@Override
						public boolean 
						messageReceived(
							Message message ) 
						{
							//System.out.println( "Received: " + connection + " - " + message );
							
							if ( message instanceof BTHandshake ){
								
								BTHandshake	bt_handshake = (BTHandshake)message;
								
									// disable az messaging so we don't need to implements parallel az message support
									// for i2p_dht and i2p_pex
								
								bt_handshake.getReserved()[5] &= ~3;
								
								torrent_hash = bt_handshake.getDataHash();
								
								if ( connection.isIncoming()){
								
									TransportEndpointTCP ep = (TransportEndpointTCP)connection.getTransport().getTransportEndpoint();
								
									SocketChannel chan = ep.getSocketChannel();
									
									if ( chan != null && !plugin.checkMixState( (InetSocketAddress)chan.socket().getRemoteSocketAddress(), torrent_hash )){
										
										throw( new RuntimeException( "Incorrect mix" ));
									}
								}else{
								
									if ( !plugin.checkMixState( address, torrent_hash )){
										
										throw( new RuntimeException( "Incorrect mix" ));
									}
								}
							}else if ( message instanceof LTHandshake ){
								
								LTMessageEncoder encoder = (LTMessageEncoder)connection.getOutgoingMessageQueue().getEncoder();
								
								Map my_extensions	 = new HashMap();
								Map their_extensions = ((LTHandshake)message).getExtensionMapping();
								
								Iterator<Object> it = their_extensions.keySet().iterator();
										
									// work out which extensions we should enable based on their declared set
								
								while( it.hasNext()){
									
									Object o = it.next();
									
									String their_key;
									
									if ( o instanceof String ){
										
										their_key = (String)o;
										
									}else if ( o instanceof byte[] ){
										
										try{
											their_key = new String((byte[])o, Constants.DEFAULT_ENCODING_CHARSET );
											
										}catch( Throwable e ){
											
											continue;
										}
										
									}else{
										
										continue;
									}
									
									if ( lt_message_ids.contains( their_key )){
										
										Object value = their_extensions.get( their_key );
										
										it.remove();
										
										my_extensions.put( their_key, value );
									}
								}
								
								if ( my_extensions.size() > 0 ){
								
									encoder.updateSupportedExtensions( my_extensions );
								}
								
								if ( encoder.supportsExtension( LTI2PPEX.MSG_ID )){
									
									encoder.addCustomExtensionHandler( 
										LTMessageEncoder.CET_PEX,
										new LTMessageEncoder.CustomExtensionHandler() {
											
											@Override
											public Object
											handleExtension(
												Object[] args ) 
											{
												PeerExchangerItem	pxi = (PeerExchangerItem)args[0];
												
												PeerItem[] adds 	= pxi.getNewlyAddedPeerConnections();
												PeerItem[] drops 	= pxi.getNewlyDroppedPeerConnections();  
												
												List<PeerItem>	my_adds;
												List<PeerItem>	my_drops;
												
												if ( adds != null && adds.length > 0 ){
													
													my_adds = new ArrayList<PeerItem>( adds.length );
													
													for ( PeerItem pi: adds ){
														
														if ( pi.getNetwork() == AENetworkClassifier.AT_I2P ){
															
															my_adds.add( pi );
														}
													}
												}else{
													
													my_adds = new ArrayList<PeerItem>(0);
												}
												
												if ( drops != null && drops.length > 0 ){
														
													my_drops = new ArrayList<PeerItem>( drops.length );
													
													for ( PeerItem pi: drops ){
														
														if ( pi.getNetwork() == AENetworkClassifier.AT_I2P ){
															
															my_drops.add( pi );
														}
													}
												}else{
													
													my_drops = new ArrayList<PeerItem>(0);
												}
												
												if ( my_adds.size() > 0 || my_drops.size() > 0 ){
													
													connection.getOutgoingMessageQueue().addMessage( new LTI2PPEX( my_adds, my_drops ),	false );
												}
	
												return( null );
											}
										});	
								}
								
								if ( encoder.supportsExtension( LTI2PDHT.MSG_ID )){
																			
											// can take a bit of time for the DHT to become available - could queue
											// missed connections for later message send I guess?
										
									I2PHelperDHT dht = plugin.selectDHT( torrent_hash );
									
									if ( dht != null ){
											
										int q_port 	= dht.getQueryPort();
										int r_port	= dht.getReplyPort();
									
										out_queue.addMessage( new LTI2PDHT( q_port, r_port ), false );
									}
								}
							}else if ( message instanceof LTI2PDHT ){
									
								LTI2PDHT i2p_dht = (LTI2PDHT)message;
								
								plugin.handleDHTPort( peer_host, i2p_dht.getQueryPort());
								
								i2p_dht.destroy();
								
								return( true );
								
							}else if ( message instanceof LTI2PPEX ){
								
								return( false );	// handled by core as instanceof AZStylePeerExchange
							}
							
							return false;
						}
						
						@Override
						public boolean isPriority() {
							return false;
						}
						
						@Override
						public void 
						dataBytesReceived(int byte_count) 
						{
							data_bytes_received += byte_count;
						}
					});
				
			}else if ( peer_host.endsWith( ".onion" )){

				connection.getOutgoingMessageQueue().registerQueueListener(
					new OutgoingMessageQueue.MessageQueueListener() 
					{						
						@Override
						public void protocolBytesSent(int byte_count) {
						}
						
						@Override
						public void messageSent(Message message) {
						}
						
						@Override
						public void messageRemoved(Message message) {
						}
						
						@Override
						public void messageQueued(Message message) {
						}
						
						@Override
						public boolean messageAdded(Message message) {
							
							if ( message instanceof BTHandshake ){
							
								BTHandshake	bt_handshake = (BTHandshake)message;
								
									// deterministically switch peer-id (factor in dht network in case we switch nets)
								
								byte[] peer_id = bt_handshake.getPeerId();
								
								peer_id = peer_id.clone();
								
								for ( int i=0;i<peer_id_mask.length;i++){
									
									peer_id[i+8] ^= peer_id_mask[i];
								}
								
								peer_id[18]++;	// tor specific

								byte	dht_index = (byte)plugin.selectDHTIndex( bt_handshake.getDataHash());
																
								peer_id[19] += dht_index;
								
								byte[] sha1 = new SHA1Simple().calculateHash( peer_id );
								
								for ( int i=8;i<20;i++){
									
									peer_id[i] = sha1[i];
								}
								
								bt_handshake.setPeerId( peer_id );
							}
							
							return true;
						}
						
						@Override
						public void flush() {
						}
						
						@Override
						public void 
						dataBytesSent(
							int byte_count) 
						{
							data_bytes_sent += byte_count;
						}
					});
				
				connection.getIncomingMessageQueue().registerQueueListener(
					new IncomingMessageQueue.MessageQueueListener() {
						
						private byte[] torrent_hash;
						
						@Override
						public void protocolBytesReceived(int byte_count) {
						}
						
						@Override
						public boolean 
						messageReceived(
							Message message ) 
						{
							//System.out.println( "Received: " + connection + " - " + message );
							
							if ( message instanceof BTHandshake ){
								
								BTHandshake	bt_handshake = (BTHandshake)message;
																
								torrent_hash = bt_handshake.getDataHash();
								
								if ( connection.isIncoming()){
								
									TransportEndpointTCP ep = (TransportEndpointTCP)connection.getTransport().getTransportEndpoint();
								
									SocketChannel chan = ep.getSocketChannel();
									
									if ( chan != null && !plugin.checkMixState( (InetSocketAddress)chan.socket().getRemoteSocketAddress(), torrent_hash )){
										
										throw( new RuntimeException( "Incorrect mix" ));
									}
								}else{
								
									if ( !plugin.checkMixState( address, torrent_hash )){
										
										throw( new RuntimeException( "Incorrect mix" ));
									}
								}
							}

							return false;
						}
						
						@Override
						public boolean isPriority() {
							return false;
						}
						
						@Override
						public void 
						dataBytesReceived(int byte_count) 
						{
							data_bytes_received += byte_count;
						}
					});
			}	
		}
	}
	
	protected void
	destroy()
	{
		NetworkConnectionFactory.removeListener( this );		
		
		for ( LTMessage message: lt_messages ){
			
			unregisterMessage( message );
		}
	}
	
	protected static class
	LTI2PDHT
		implements LTMessage
	{
		public static String 	MSG_ID			= "i2p_dht";
		public static byte[] 	MSG_ID_BYTES 	= MSG_ID.getBytes();
		public static int 		SUBID_I2P_DHT	= 10;
		
		private DirectByteBuffer buffer = null;
		  
		private int		q_port;
		private int		r_port;
		
		public
		LTI2PDHT()
		{
		}
		
		public
		LTI2PDHT(
			int		_q_port,
			int		_r_port )
		{
			q_port		= _q_port;
			r_port		= _r_port;
		}
		
		public 
		LTI2PDHT(
			Map					map,
			DirectByteBuffer	data )
		{
			q_port = ((Long)map.get("port")).intValue();
			r_port = ((Long)map.get("rport")).intValue();
		}
		
		@Override
		public String
		getID()
		{
			return( MSG_ID );
		}

		@Override
		public byte[]
		getIDBytes()
		{
			return( MSG_ID_BYTES );
		}

		@Override
		public String getFeatureID() {  return LTMessage.LT_FEATURE_ID;  }
		@Override
		public int getFeatureSubID() { return SUBID_I2P_DHT;  }
		@Override
		public int getType() {  return Message.TYPE_PROTOCOL_PAYLOAD;  }
		@Override
		public byte getVersion() { return BTMessageFactory.MESSAGE_VERSION_INITIAL; };


		@Override
		public String
		getDescription()
		{
			return( MSG_ID );
		}

		public int
		getQueryPort()
		{
			return( q_port );
		}
		
		@Override
		public DirectByteBuffer[]
		getData()
		{
			if ( buffer == null ){
				
				Map payload_map = new HashMap();

				payload_map.put( "port", new Long(q_port));
				payload_map.put( "rport", new Long(r_port));
								
				buffer = MessagingUtil.convertPayloadToBencodedByteStream(payload_map, DirectByteBuffer.AL_MSG );
			}

			return new DirectByteBuffer[]{ buffer };  
		}

		@Override
		public Message
		deserialize( 
			DirectByteBuffer 	data, 
			byte 				version ) 

			throws MessageException
		{
			int	pos = data.position( DirectByteBuffer.SS_MSG );
			
			byte[] dict_bytes = new byte[ Math.min( 128, data.remaining( DirectByteBuffer.SS_MSG )) ];
						
			data.get( DirectByteBuffer.SS_MSG, dict_bytes );
			
			try{
				Map root = BDecoder.decode( dict_bytes );

				data.position( DirectByteBuffer.SS_MSG, pos + BEncoder.encode( root ).length );			
									
				return( new LTI2PDHT( root, data ));
				
			}catch( Throwable e ){
				
				e.printStackTrace();
				
				throw( new MessageException( "decode failed", e ));
			}
		}


		@Override
		public void
		destroy()
		{
			if ( buffer != null ){
				
				buffer.returnToPool();
			}
		}
	}
	
	protected static class
	LTI2PPEX
		implements AZStylePeerExchange, LTMessage
	{
		public static String 	MSG_ID			= "i2p_pex";
		public static byte[] 	MSG_ID_BYTES 	= MSG_ID.getBytes();
		public static int 		SUBID_I2P_PEX	= 11;
		
		private DirectByteBuffer buffer = null;
		  
		private	PeerItem[]		added		= new PeerItem[0];
		private	PeerItem[]		dropped		= new PeerItem[0];
		
		public
		LTI2PPEX()
		{
		}
		
		public
		LTI2PPEX(
			List<PeerItem>		_adds,
			List<PeerItem>		_drops )
		{
			added 	= _adds.toArray( new PeerItem[ _adds.size()]);
			dropped = _drops.toArray( new PeerItem[ _drops.size()]);
		}
		
		public 
		LTI2PPEX(
			Map					map,
			DirectByteBuffer	data )
		{
			byte[] added_hashes = (byte[])map.get( "added" );
					
			if ( added_hashes != null ){
				
				added	= decodePeers( added_hashes );
			}
			
			byte[] dropped_hashes = (byte[])map.get( "dropped" );
			
			if ( dropped_hashes != null ){
				
				dropped	= decodePeers( dropped_hashes );
			}
		}
		
		private PeerItem[]
		decodePeers(
			byte[]	hashes )
		{
			int	hashes_len = hashes.length;
			
			if ( hashes_len % 32 != 0 ){
				
				return( new PeerItem[0]);
			}
			
			PeerItem[] result = new PeerItem[hashes_len/32];
			
			int	pos = 0;
			
			for ( int i=0;i<hashes_len;i+=32 ){
				
				byte[]	bytes = new byte[32];
				
				System.arraycopy( hashes, i, bytes, 0, 32 );
				
				String host = Base32.encode( bytes ) + ".b32.i2p";
								
				PeerItem peer = 
					PeerItemFactory.createPeerItem( 
						host, 
						6881, 
						PeerItemFactory.PEER_SOURCE_PEER_EXCHANGE, 
						PeerItemFactory.HANDSHAKE_TYPE_PLAIN,
						0, 
						PeerItemFactory.CRYPTO_LEVEL_1,
						0 );
				
				result[pos++] = peer;
			}
			
			return( result );
		}
		
		@Override
		public String
		getID()
		{
			return( MSG_ID );
		}

		@Override
		public byte[]
		getIDBytes()
		{
			return( MSG_ID_BYTES );
		}

		@Override
		public String getFeatureID() {  return LTMessage.LT_FEATURE_ID;  }
		@Override
		public int getFeatureSubID() { return SUBID_I2P_PEX;  }
		@Override
		public int getType() {  return Message.TYPE_PROTOCOL_PAYLOAD;  }
		@Override
		public byte getVersion() { return BTMessageFactory.MESSAGE_VERSION_INITIAL; };


		@Override
		public String
		getDescription()
		{
			return( MSG_ID );
		}
		
		@Override
		public DirectByteBuffer[]
		getData()
		{
			if ( buffer == null ){
				
				Map payload_map = new HashMap();

				encodePeers( added, payload_map, "added" );
				encodePeers( dropped, payload_map, "dropped" );
					
				//System.out.println( "Sending " + payload_map );
				
				buffer = MessagingUtil.convertPayloadToBencodedByteStream(payload_map, DirectByteBuffer.AL_MSG );
			}

			return new DirectByteBuffer[]{ buffer };  
		}

		private void
		encodePeers(
			PeerItem[]		peers,
			Map				map,
			String			key )
		{
			int peers_length = peers.length;
			
			if ( peers_length > 0 ){
				
				byte[]	hashes = new byte[peers_length*32];
				
				int	pos = 0;
				
				for ( int i=0;i<peers_length;i++){
					
					String host = peers[i].getAddressString();
					
					if ( host.endsWith( ".b32.i2p" )){
						
						byte[] h = Base32.decode( host.substring( 0, host.length() - 8 ));
						
						if ( h.length == 32 ){
							
							System.arraycopy( h, 0, hashes, pos, 32 );
							
							pos += 32;
						}
					}
				}
				
				if ( pos < hashes.length ){
					
					if ( pos > 0 ){
						
						byte[] temp = new byte[pos];
						
						System.arraycopy( hashes, 0, temp, 0, pos );
						
						hashes = temp;
					}
				}
				
				if ( pos > 0 ){
				
					map.put( key, hashes );
				}
			}
		}
		
		@Override
		public PeerItem[]
		getAddedPeers()
		{
			return( added );
		}
		
		@Override
		public PeerItem[]
		getDroppedPeers()
		{
			return( dropped );
		}
		
		@Override
		public int
		getMaxAllowedPeersPerVolley(
			boolean initial, 
			boolean added )
		{
			return (initial && added) ? 500 : 250;
		}
		  
		@Override
		public Message
		deserialize( 
			DirectByteBuffer 	data, 
			byte 				version ) 

			throws MessageException
		{
			 Map root = MessagingUtil.convertBencodedByteStreamToPayload(data, 2, getID());		
									
			 return( new LTI2PPEX( root, data ));
		}

		@Override
		public void
		destroy()
		{
			if ( buffer != null ){
				
				buffer.returnToPool();
			}
		}
	}
}
