/*
 * Created on May 15, 2014
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

import java.util.*;

import com.biglybt.core.util.AENetworkClassifier;
import com.biglybt.core.util.AERunnable;
import com.biglybt.core.util.AsyncDispatcher;
import com.biglybt.core.util.BDecoder;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.core.util.TimerEventPeriodic;
import com.biglybt.core.util.TorrentUtils;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadAttributeListener;
import com.biglybt.pif.download.DownloadManager;
import com.biglybt.pif.download.DownloadManagerListener;
import com.biglybt.pif.download.DownloadScrapeResult;
import com.biglybt.pif.peers.Peer;
import com.biglybt.pif.peers.PeerManager;
import com.biglybt.pif.peers.PeerStats;
import com.biglybt.pif.torrent.Torrent;
import com.biglybt.pif.torrent.TorrentAttribute;
import com.biglybt.pif.torrent.TorrentManager;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.config.Parameter;
import com.biglybt.pif.ui.config.ParameterListener;
import com.biglybt.pifimpl.local.PluginCoreUtils;
import org.parg.azureus.plugins.networks.i2p.router.I2PHelperRouter;
import org.parg.azureus.plugins.networks.i2p.tracker.I2PDHTTrackerPluginListener;

import com.biglybt.plugin.net.buddy.BuddyPluginBeta.ChatInstance;
import com.biglybt.plugin.net.buddy.BuddyPluginBeta.ChatMessage;
import com.biglybt.plugin.net.buddy.BuddyPluginBeta.ChatAdapter;
import com.biglybt.plugin.net.buddy.BuddyPluginBeta;
import com.biglybt.plugin.net.buddy.BuddyPluginUtils;

public class 
I2PHelperNetworkMixer 
	implements DownloadAttributeListener, DownloadManagerListener, I2PDHTTrackerPluginListener
{
	private static final int MS_NONE				= 0;
	private static final int MS_CHANGING			= 1;
	private static final int MS_ACTIVE				= 2;
	private static final int MS_MANUAL				= 3;
	
	private I2PHelperPlugin		plugin;
	private PluginInterface		plugin_interface;
	private TorrentAttribute 	ta_networks;
	private TorrentAttribute 	ta_mixstate;
	private TorrentAttribute 	ta_sr_code;

	private volatile boolean		mix_enabled;
	private volatile boolean		seed_requests_enabled;
	
	private int						incomplete_limit;
	private int						complete_limit;
	
	private static final int TIMER_PERIOD 	= 30*1000;
	
	private static final int PRI_PEER_CHECK_PERIOD 	= 30*1000;
	private static final int PRI_PEER_CHECK_TICKS 	= PRI_PEER_CHECK_PERIOD/TIMER_PERIOD;

	private static final int MIX_CHECK_PERIOD_MIN 	= 60*1000;
	
	private static final int MIX_CHECK_PERIOD 		= 5*60*1000;
	private static final int MIX_CHECK_TICKS 		= MIX_CHECK_PERIOD/TIMER_PERIOD;
	
	private static final int SEED_REQUEST_CHECK_PERIOD 		= 1*60*1000;
	private static final int SEED_REQUEST_CHECK_TICKS 		= SEED_REQUEST_CHECK_PERIOD/TIMER_PERIOD;
	
	private static final int SEED_REQUEST_RESET_PERIOD		= 30*60*1000;
	private static final int SEED_REQUEST_RESET_TICKS		= SEED_REQUEST_RESET_PERIOD/TIMER_PERIOD;
			
	private static final int SR_CODE_FAILED 	= -1;
	private static final int SR_CODE_NOT_SET	= 0;
	
	
	
	private static final int MIN_ACTIVE_PERIOD 				= 20*60*1000;
	private static final int MIN_ACTIVE_WITH_PEERS_PERIOD 	= 60*60*1000;
	
	private TimerEventPeriodic	recheck_timer;
	
	private long		last_check		= -MIX_CHECK_PERIOD_MIN;
	private TimerEvent	check_pending 	= null;
	private boolean		check_active;
	
	private AsyncDispatcher	dispatcher = new AsyncDispatcher( "i2pmixer", 5*1000 );
	
	private boolean	destroyed;
	
	protected
	I2PHelperNetworkMixer(
		I2PHelperPlugin			_plugin,
		final BooleanParameter	enable_mix_param,
		final BooleanParameter	enable_seed_requests_param,
		final IntParameter		incomp_param,
		final IntParameter		comp_param )
	{
		plugin				= _plugin;
		plugin_interface	= plugin.getPluginInterface();
		
		ta_networks 	= plugin_interface.getTorrentManager().getAttribute( TorrentAttribute.TA_NETWORKS );

		TorrentManager tm = plugin_interface.getTorrentManager();
		
		ta_mixstate = tm.getPluginAttribute( "mixstate" );
		ta_sr_code	= tm.getPluginAttribute( "srcode" );
		
		ParameterListener	listener = 
			new ParameterListener() 
			{	
				@Override
				public void
				parameterChanged(
					Parameter param )
				{
					synchronized( I2PHelperNetworkMixer.this ){
						
						mix_enabled				= enable_mix_param.getValue();
						seed_requests_enabled	= enable_seed_requests_param.getValue();
						
						incomplete_limit	= incomp_param.getValue();
						complete_limit		= comp_param.getValue();
					}
					
					parametersChanged();
				}
			};
			
		listener.parameterChanged( null );
		
		enable_mix_param.addListener( listener );
		enable_seed_requests_param.addListener( listener );
		
		incomp_param.addListener( listener );
		comp_param.addListener( listener );
		
		plugin_interface.getDownloadManager().addListener( this, false );
		
		Download[] downloads = plugin_interface.getDownloadManager().getDownloads();
		
		for ( Download download: downloads ){
			
			int	existing_state = download.getIntAttribute( ta_mixstate );
			
			if ( existing_state == MS_NONE ){
				
				if ( PluginCoreUtils.unwrap( download ).getDownloadState().isNetworkEnabled( AENetworkClassifier.AT_I2P )){
					
					download.setIntAttribute( ta_mixstate, MS_MANUAL );
					
					existing_state = MS_MANUAL;
				}
			}
			
			if ( existing_state != MS_MANUAL ){
			
				download.addAttributeListener( this, ta_networks, DownloadAttributeListener.WRITTEN );
			}
			
			addSRCode( download );
		}
		
		recheck_timer = 
			SimpleTimer.addPeriodicEvent(
				"i2pmixrecheck",
				TIMER_PERIOD,
				new TimerEventPerformer()
				{
					private int	tick_count = 0;
					
					@Override
					public void
					perform(
						TimerEvent event ) 
					{
						tick_count++;
						
						if ( tick_count % PRI_PEER_CHECK_TICKS == 0 ){
							
							checkPriorityPeers();
						}
						
						if ( tick_count % MIX_CHECK_TICKS == 0 ){
							
							checkMixedDownloads();
						}
						
						if ( tick_count % SEED_REQUEST_CHECK_TICKS == 0 ){
							
							checkSeedRequests();
						}
						
						if ( tick_count % SEED_REQUEST_RESET_TICKS == 0 ){
							
							resetSeedRequests();
						}
					}
				});
	}
		
	private void
	parametersChanged()
	{
		if ( mix_enabled ){
			
			checkMixedDownloads();
			
		}else{
			
			Download[] downloads = plugin_interface.getDownloadManager().getDownloads();
			
			for ( Download download: downloads ){
				
				if ( download.getIntAttribute( ta_mixstate ) == MS_ACTIVE ){
					
					setNetworkState( download, false );
				}
			}
		}
	}
	
	private int
	calculateSRCode(
		Download		download )
	{
			// ensure we never return 0 so we can distinguish an actual code from an unset torrent attribute
		
		try{
			Torrent torrent = download.getTorrent();
			
			if ( torrent == null ){
				
				return( SR_CODE_FAILED );
			}
			
			byte[][] hashes = download.getTorrent().getPieces();
			
			for ( byte[] hash: hashes ){
				
				for ( int i=0;i<hash.length-2;i+=3){
				
					int code = (( hash[i]&0xff) << 16 ) + (( hash[i+1]&0xff ) << 8 )  + ( hash[i+2]&0xff );
				
					if ( code != 0 ){
						
						return( code );
					}
				}
			}
			
			return( 1 );
			
		}catch( Throwable e ){
			
			return( SR_CODE_FAILED );
		}
	}
	
	private Map<Integer,Download>	sr_code_map = new HashMap<Integer, Download>();
	
	private int
	getSRCode(
		Download		download )
	{
		int code = download.getIntAttribute( ta_sr_code );
		
			// should always be set...
		
		if ( code == SR_CODE_NOT_SET ){
			
			code = SR_CODE_FAILED;
		}
		
		return( code );
	}
	
	private void
	addSRCode(
		Download		download )
	{
		if ( !download.isPersistent()){
			
			return;		
		}
		
		int	sr_code = download.getIntAttribute( ta_sr_code );
		
		if ( sr_code == SR_CODE_NOT_SET ){
					
			sr_code = calculateSRCode( download );
			
			download.setIntAttribute( ta_sr_code, sr_code );	
		}
		
		if ( sr_code != SR_CODE_FAILED ){
			
				// small chance of a clash but no real point in maintaining a list of downloads
				// to cover it
			
			synchronized( sr_code_map ){
					
				sr_code_map.put( sr_code, download );
			}
		}
	}
	
	private void
	removeSRCode(
		Download		download )
	{
		if ( !download.isPersistent()){
			
			return;
		}
		
		int	sr_code = download.getIntAttribute( ta_sr_code );

		if ( sr_code != SR_CODE_NOT_SET ){
			
			synchronized( sr_code_map ){
				
				sr_code_map.remove( sr_code );
			}
		}
		
		synchronized( seed_request_downloads ){
		
			seed_request_downloads.remove( download );
		}
	}
	
	@Override
	public void
	downloadAdded(
		Download	download )
	{
		int	existing_state = download.getIntAttribute( ta_mixstate );

		if ( existing_state == MS_NONE ){
			
			if ( PluginCoreUtils.unwrap( download ).getDownloadState().isNetworkEnabled( AENetworkClassifier.AT_I2P )){
				
				download.setIntAttribute( ta_mixstate, MS_MANUAL );
				
				existing_state = MS_MANUAL;
			}
		}
		
		if ( existing_state != MS_MANUAL ){
			
			download.addAttributeListener( this, ta_networks, DownloadAttributeListener.WRITTEN );
			
			if ( mix_enabled ){
				
				checkMixedDownloads();
			}
		}
		
		addSRCode( download );
	}
	
	@Override
	public void
	downloadRemoved(
		Download	download )
	{
		if ( mix_enabled ){
			
			checkMixedDownloads();
		}
		
		removeSRCode( download );
	}
	
	private static final String seed_request_write_key = "Statistics: Announce: Requests";
	
	private Object					seed_request_write_lock = new Object();
	private volatile ChatInstance	seed_request_write_chat;
	private Set<Download>			seed_requests_write_pending	= new HashSet<Download>();
	private Set<Integer>			seed_request_recent_writes	= new HashSet<Integer>();
	
	private static final String seed_request_read_key = "Statistics: Announce: Summary[pk=AR4QZBLCNKCC7ONS2ZTHZJMIO6UB3AM2X3VRQDYYSBUBDDMBSEA5KXUTCUQID4AS5AP4AMUA4MUW67Y&ro=1]";
	
	private Object					seed_request_read_lock = new Object();
	private volatile ChatInstance	seed_request_read_chat;

	private void
	resetSeedRequests()
	{
		synchronized( seed_request_recent_writes ){
			
			seed_request_recent_writes.clear();
		}
	}
	
	private void
	checkSeedRequests()
	{
		if ( !seed_requests_enabled ){
			
			return;
		}
		
		checkIncomingSeedRequests();
		
		checkOutgoingSeedRequests();
	}
	
	private void
	checkIncomingSeedRequests()
	{
		if ( !mix_enabled ){
			
				// no point in monitoring seed requests if we're not going to be able 
				// to mix in a torrent in response
			
			return;
		}
		
		if ( !BuddyPluginUtils.isBetaChatAvailable()){
			
			return;
		}
		
		synchronized( seed_request_read_lock ){

			if ( seed_request_read_chat != null && seed_request_read_chat.isDestroyed()){
				
				seed_request_read_chat = null;
			}
			
			if ( seed_request_read_chat == null ){
				
				dispatcher.dispatch(
						new AERunnable()
						{
							@Override
							public void
							runSupport() 
							{
								synchronized( seed_request_read_lock ){
									
									if ( seed_request_read_chat != null ){
										
										return;
									}
	
									try{
										Map<String,Object>	options = new HashMap<String, Object>();
										
										options.put( ChatInstance.OPT_INVISIBLE, true );
																		
										final ChatInstance chat = 
												BuddyPluginUtils.getChat(
													AENetworkClassifier.AT_PUBLIC, 
													seed_request_read_key,
													options );
		
										chat.setSharedNickname( false );
										
										chat.addListener(
											new ChatAdapter()
											{
												@Override
												public void 
												messageReceived(
													ChatMessage 	msg,
													boolean			sort_outstanding )
												{
													receiveIncomingSeedRequest( msg );
												}
											});
										
										List<ChatMessage>	messages = chat.getMessages();
										
										for ( ChatMessage msg: messages ){
											
											receiveIncomingSeedRequest( msg );
										}
										
										seed_request_read_chat = chat;
										
									}catch( Throwable e ){
										
										e.printStackTrace();
									}
								}
							}
						});
				
				return;
			}
		}
	}
	
	private void
	receiveIncomingSeedRequest(
		ChatMessage		msg )
	{
		if ( msg.getMessageType() != ChatMessage.MT_NORMAL ){
			
			return;
		}
		
		byte[] bytes = msg.getRawMessage();
		
		try{
			Map<String,Object> map = BDecoder.decode( bytes );
			
			byte[] codes = (byte[])map.get( "c" );
			
			if ( codes != null ){
				
				for ( int i=0;i<codes.length;i+=3 ){
					
					int	code = (( codes[i]<<16)&0xff0000 ) | (( codes[i+1]<<8)&0xff00 ) | ( codes[i+2] & 0xff );
					
					//System.out.println( "got code: " + Integer.toHexString( code ));
					
					synchronized( sr_code_map ){
						
						Download download = sr_code_map.get( code );
						
						if ( download != null ){
							
							handleIncomingSeedRequest( download );
						}
					}
				}
				
			}else{
				/* bloom filters don't work as too many false positives and too little savings if capacity
				 * increased to reduce them
				 
				Map<String,Object>	bf = (Map<String,Object>)map.get( "b" );
				
				if ( bf != null ){
					
					BloomFilter bloom = BloomFilterFactory.deserialiseFromMap( bf );
					
					System.out.println( "got bloom: " + bloom );
					
					synchronized( sr_code_map ){
						
						for ( Map.Entry<Integer, Download> entry: sr_code_map.entrySet()){
							
							int code = entry.getKey();
							
							byte[]	code_bytes = { (byte)(code>>>16), (byte)(code>>>8), (byte)code };
							
							if ( bloom.contains( code_bytes )){
								
								Download download = entry.getValue();
								
								System.out.println( "     matched " + code + " to " + download.getName());
							}
						}
					}
				}
				*/
			}
		}catch( Throwable e ){
			
		}
	}
	
	private static final int MAX_SEED_REQUEST_DOWNLOADS	= 5;
	
	private Map<Download,Long>	seed_request_downloads = 
		new LinkedHashMap<Download,Long>(MAX_SEED_REQUEST_DOWNLOADS,0.75f,true)
		{
			@Override
			protected boolean
			removeEldestEntry(
		   		Map.Entry<Download,Long> eldest ) 
			{
				return size() > MAX_SEED_REQUEST_DOWNLOADS;
			}
		};
		
	private void
	handleIncomingSeedRequest(
		Download		download )
	{
		if ( !download.isPersistent()){
			
			return;
		}
		
		int state = download.getState();
		
		if ( state == Download.ST_ERROR || state == Download.ST_STOPPED ){
			
			return;
		}
		
		boolean	new_request;
		
		synchronized( seed_request_downloads ){
		
			new_request = seed_request_downloads.put( download, SystemTime.getMonotonousTime()) == null;
		}
		
		if ( new_request ){
		
			plugin.log( "Netmix: seed request for " + download.getName());
		}
		
		checkMixedDownloads();
	}
	
	private void
	checkOutgoingSeedRequests()
	{
		if ( !BuddyPluginUtils.isBetaChatAnonAvailable()){
			
			return;
		}
		
		synchronized( seed_request_write_lock ){

			if ( seed_request_write_chat != null && seed_request_write_chat.isDestroyed()){
				
				seed_request_write_chat = null;
			}
			
			if ( seed_request_write_chat == null ){
										
				if ( seed_requests_write_pending.size() == 0 ){
						
					return;
				}
				
				dispatcher.dispatch(
						new AERunnable()
						{
							@Override
							public void
							runSupport() 
							{
								synchronized( seed_request_write_lock ){
									
									if ( seed_request_write_chat != null ){
										
										return;
									}
	
									try{
										Map<String,Object>	options = new HashMap<String, Object>();
										
										options.put( ChatInstance.OPT_INVISIBLE, true );
																		
										ChatInstance chat = 
												BuddyPluginUtils.getChat(
													AENetworkClassifier.AT_I2P, 
													seed_request_write_key,
													options );
		
										chat.setSharedNickname( false );
										
										seed_request_write_chat = chat;
										
									}catch( Throwable e ){
										
										e.printStackTrace();
									}
								}
							}
						});
				
				return;
			}
		}
		
		if ( seed_request_write_chat.getIncomingSyncState() != 0 ){
			
			return;
		}
		
		synchronized( seed_request_write_lock ){
			
			if ( seed_requests_write_pending.size() == 0 ){
				
				return;
			}
			
			Iterator<Download>	it = seed_requests_write_pending.iterator();
			
			String	message = "";
			
			int	num_added = 0;
			
			while( it.hasNext()){
				
				Download d = it.next();
				
				it.remove();
				
				int code = getSRCode( d );
				
				if ( code != SR_CODE_FAILED ){
					
					synchronized( seed_request_recent_writes ){
						
						if ( seed_request_recent_writes.contains( code )){
							
							continue;
						}
						
						seed_request_recent_writes.add( code );
					}
					
					message += (message.length()==0?"":" ") + Integer.toHexString( code );
					
					num_added++;
					
					if ( num_added >= 20 ){
						
						break;
					}
				}
			}
			
			if ( num_added > 0 ){
				
				Map<String,Object>	flags 	= new HashMap<String, Object>();
				
				flags.put( BuddyPluginBeta.FLAGS_MSG_ORIGIN_KEY, BuddyPluginBeta.FLAGS_MSG_ORIGIN_SEED_REQ );
				
				Map<String,Object>	options = new HashMap<String, Object>();
	
				seed_request_write_chat.sendMessage( message, flags, options );
			}
		}
	}
	
	@Override
	public void
	trackingStarted(
		Download		download,
		I2PHelperDHT	dht )
	{
		if ( 	dht.getDHTIndex() != I2PHelperRouter.DHT_NON_MIX || 
				download.getFlag( Download.FLAG_LOW_NOISE ) ||
				download.isComplete()){
			
			return;
		}
		
		String[] nets = PluginCoreUtils.unwrap( download ).getDownloadState().getNetworks();
		
		boolean	found_non_pub = false;
		
		for ( String net: nets ){
			
			if ( net == AENetworkClassifier.AT_PUBLIC ){
				
				return;
				
			}else{
				
				found_non_pub = true;
			}
		}
		
		if ( !found_non_pub ){
			
			return;
		}
				
		synchronized( seed_request_write_lock ){
			
			seed_requests_write_pending.add( download );
		}
	}
	
	@Override
	public void
	trackingStopped(
		Download		download,
		I2PHelperDHT	dht )
	{
		if ( dht.getDHTIndex() != I2PHelperRouter.DHT_NON_MIX || download.getFlag( Download.FLAG_LOW_NOISE ) ){
			
			return;
		}
		
		synchronized( seed_request_write_lock ){
			
			seed_requests_write_pending.remove( download );
		}
	}
	
	private void
	checkPriorityPeers()
	{
		DownloadManager dm = plugin_interface.getDownloadManager();
		
		Download[]	downloads = dm.getDownloads();
				
		for ( Download download: downloads ){
			
			if ( download.getState() == Download.ST_SEEDING ){
				
				String[] nets = PluginCoreUtils.unwrap( download ).getDownloadState().getNetworks();
				
				if ( nets.length == 1 ){
					
					continue;
				}
				
				boolean	found_pub = false;
				
				for ( String net: nets ){
					
					if ( net == AENetworkClassifier.AT_PUBLIC ){
						
						found_pub = true;
					}
				}
				
				if ( !found_pub ){
					
					continue;
				}
				
				PeerManager pm = download.getPeerManager();
				
				if ( pm == null ){
					
					continue;
				}
				
				Peer[] peers = pm.getPeers();
				
				List<Peer> i2p_peers = new ArrayList<Peer>(peers.length);
				
				int	priority = 0;
				
				for ( Peer peer: peers ){
					
					String cat =  AENetworkClassifier.categoriseAddress( peer.getIp());
					
					if ( cat != AENetworkClassifier.AT_PUBLIC ){
						
						i2p_peers.add( peer );
						
						if ( peer.isPriorityConnection()){
							
							priority++;
						}
					}
				}
				
				if ( i2p_peers.size() > 0 && priority < 2 ){
					
					for ( Peer peer: i2p_peers ){
						
						if ( !peer.isPriorityConnection()){
							
							peer.setPriorityConnection( true );
							
							priority++;
							
							if ( priority == 2 ){
								
								break;
							}
						}
					}
				}
			}
		}
	}
	
	private void
	checkMixedDownloads()
	{
		synchronized( this ){
			
			if ( destroyed ){
				
				return;
			}
			
			if ( check_pending != null ){
				
				return;
			}
			
			long now = SystemTime.getMonotonousTime();
			
			long time_since_last_check = now - last_check;
			
			if ( time_since_last_check < MIX_CHECK_PERIOD_MIN ){
				
				check_pending = 
					SimpleTimer.addEvent(
						"i2pmixer",
						SystemTime.getCurrentTime() + ( MIX_CHECK_PERIOD_MIN - time_since_last_check ),
						new TimerEventPerformer() 
						{
							@Override
							public void
							perform(
								TimerEvent event ) 
							{
								synchronized( I2PHelperNetworkMixer.this ){
									
									check_pending = null;
								}
								
								checkMixedDownloads();
							}
						});
				
				return;
			}
			
			last_check = now;
		}
		
		dispatcher.dispatch(
			new AERunnable()
			{
				@Override
				public void
				runSupport() 
				{
					synchronized( I2PHelperNetworkMixer.this ){
						
						if ( check_active || destroyed ){
							
							return;
						}
						
						check_active = true;
					}
					
					try{
						checkMixedDownloadsSupport();
						
					}finally{
						
						synchronized( I2PHelperNetworkMixer.this ){
							
							check_active = false;
						}
					}
				}
			});
	}
	
	private Comparator<Download> download_comparator =
			new Comparator<Download>() 
			{
				@Override
				public int
				compare(
					Download 	d1, 
					Download 	d2 ) 
				{
					boolean	d1_sr = seed_request_downloads.containsKey( d1 );
					boolean	d2_sr = seed_request_downloads.containsKey( d2 );
					
					if ( d1_sr || d2_sr ){
						
						if ( d1_sr && d2_sr ){
							
							long l = d2.getCreationTime() - d1.getCreationTime();
							
							if ( l < 0 ){
								return( -1 );
							}else if ( l > 0 ){
								return( 1 );
							}else{
								return(0);
							}
						}else if ( d1_sr ){
							return( -1 );
						}else{
							return( 1 );
						}
					}
					
					DownloadScrapeResult s1 = d1.getAggregatedScrapeResult();
					DownloadScrapeResult s2 = d2.getAggregatedScrapeResult();
					
					if ( s1 == s2 ){
						
						return( 0 );
						
					}else if ( s1 == null ){
						
						return( 1 );
	
					}else if ( s2 == null ){
						
						return( -1 );
						
					}else{
						
						return( s2.getSeedCount() - s1.getSeedCount());
					}
				}
			};
			
	private void
	checkMixedDownloadsSupport()
	{
		DownloadManager dm = plugin_interface.getDownloadManager();
		
		Download[]	downloads = dm.getDownloads();
		
		List<Download>	complete_downloads		= new ArrayList<Download>( downloads.length );
		List<Download>	incomplete_downloads 	= new ArrayList<Download>( downloads.length );
		
		for ( Download download: downloads ){
			
			if ( 	download.getFlag( Download.FLAG_LOW_NOISE ) || 
					download.getFlag( Download.FLAG_METADATA_DOWNLOAD )){
				
				continue;
			}
			
			Torrent torrent = download.getTorrent();
			
			if ( torrent == null ){
				
				continue;
			}
			
			if ( TorrentUtils.isReallyPrivate( PluginCoreUtils.unwrap( torrent ))){
				
				continue;
			}
			
			if ( !PluginCoreUtils.unwrap( download ).getDownloadState().isNetworkEnabled( AENetworkClassifier.AT_PUBLIC )){
				
				continue;
			}
			
			int	existing_state = download.getIntAttribute( ta_mixstate );
			
			if ( existing_state == MS_CHANGING || existing_state == MS_MANUAL ){
				
				continue;
			}
			
			int download_state = download.getState();
		
			if ( 	download_state == Download.ST_ERROR || 
					download_state == Download.ST_STOPPING || 
					download_state == Download.ST_STOPPED ){
				
				continue;
			}
			
			boolean	complete = download.isComplete();
			
			if ( complete ){
				
				complete_downloads.add( download );
				
			}else{
				
				incomplete_downloads.add( download );
			}
		}
		
		applyRules( complete_downloads, complete_limit );
		applyRules( incomplete_downloads, incomplete_limit );
	}
	
	private void
	applyRules(
		List<Download>		downloads,
		int					active_limit )
	{	
		if ( active_limit <= 0 ){
			
			active_limit = Integer.MAX_VALUE;
		}
				
		int	sr_count;
		
		synchronized( seed_request_downloads ){
		
			Collections.sort( downloads, download_comparator );
		
			sr_count = seed_request_downloads.size();
		}
		
		int sr_to_activate = 0;
		
			// downloads are sorted highest priority first
		
		List<Download>		to_activate 			= new ArrayList<Download>( downloads.size());
		List<Download>		failed_to_deactivate	= new ArrayList<Download>();
		
		long	now = SystemTime.getMonotonousTime() ;
		
			// remember downloads are ordered with seed-requests first and then in decreasing
			// priority for activation
		
		for ( int i=0;i<downloads.size();i++){
			
			Download download = downloads.get( i );
			
			int	existing_state = download.getIntAttribute( ta_mixstate );

			boolean	is_sr;
			
			if ( sr_count > 0 ){
				
				synchronized( seed_request_downloads ){
					
					is_sr = seed_request_downloads.containsKey( download );
				}
			}else{
				
				is_sr = false;
			}
			
			if ( existing_state == MS_ACTIVE ){
				
				if ( i < active_limit ){
					
					// already good
					
				}else{
					
					Long	activate_time = (Long)download.getUserData( I2PHelperNetworkMixer.class );
					
					if ( activate_time == null ){
						
						activate_time = 0L;	// shouldn't happen but just in case
					}
					
					long	elapsed = now - activate_time;
					
					if ( elapsed >= MIN_ACTIVE_PERIOD ){
												
						boolean	prevent_deactivation = false;
						
						if ( elapsed < MIN_ACTIVE_WITH_PEERS_PERIOD ){
							
							PeerManager pm = download.getPeerManager();
							
							if ( pm != null ){

								if ( is_sr ){
									
									prevent_deactivation = true;
									
								}else{
									
									Peer[] peers = pm.getPeers();
									
									for ( Peer peer: peers ){
										
										PeerStats stats = peer.getStats();
										
										long mins_connected = stats.getTimeSinceConnectionEstablished()/(60*1000);
										
											// 5k a min sounds like a reasonable test of liveness...
										
										if ( stats.getTotalReceived() + stats.getTotalSent() >= mins_connected*5*1024 ){
											
											String peer_net = AENetworkClassifier.categoriseAddress( peer.getIp());
											
											if ( peer_net == AENetworkClassifier.AT_I2P ){
												
												prevent_deactivation = true;
												
												break;
											}
										}
									}
								}
							}
						}
						
						if ( prevent_deactivation ){
							
							failed_to_deactivate.add( download );
							
						}else{
							
							plugin.log( "Netmix: deactivating " + download.getName() + ", sr=" + is_sr );
							
							setNetworkState( download, false );
							
							if ( is_sr ){
								
									// it's had its chance, kill the seed request 
								
								synchronized( seed_request_downloads ){
	
									seed_request_downloads.remove( download );
								}
							}
						}
					}else{
						
						failed_to_deactivate.add( download );
					}
				}
			}else{
				
				if ( i < active_limit ){
					
					if ( is_sr ){
					
						sr_to_activate++;
					}
					
					to_activate.add( download );
				}
			}
		}
		
			// if we failed to deactivate N downloads then that means we can't yet activate N
			// new ones
		
		int	num_to_activate = to_activate.size() - failed_to_deactivate.size();
		
		int	force_active = sr_to_activate - num_to_activate;
		
		if ( force_active > 0 ){
		
				// we really want to try and get seed requests active so see if we can force-deactivate
				// some that failed normal deactivation
			
			for ( int i=failed_to_deactivate.size()-1; i>=0; i-- ){
				
				Download download = failed_to_deactivate.get(i);
				
				boolean	is_sr;
				
				synchronized( seed_request_downloads ){
					
					is_sr = seed_request_downloads.containsKey( download );
				}
				
				if ( !is_sr ){
					
					plugin.log( "Netmix: force deactivating " + download.getName());
					
					setNetworkState( download, false );
					
					num_to_activate++;
					
					force_active--;
					
					if ( force_active == 0 ){
						
						break;
					}
				}
			}
		}
		
		for ( int i=0;i<num_to_activate;i++){
			
			Download download = to_activate.get(i);
			
			boolean	is_sr;
			
			synchronized( seed_request_downloads ){
				
				is_sr = seed_request_downloads.containsKey( download );
			}
			
			plugin.log( "Netmix: activating " + download.getName() + ", sr=" + is_sr );
			
			setNetworkState( download, true );
		}
	}
	
	protected void
	checkMixState(
		Download		download )
	{
		if ( !mix_enabled ){
			
			return;
		}
		
		int download_state = download.getState();
		
		if ( 	download_state == Download.ST_ERROR || 
				download_state == Download.ST_STOPPING || 
				download_state == Download.ST_STOPPED ){
			
			return;
		}
		
		int	existing_state = download.getIntAttribute( ta_mixstate );

		if ( existing_state == MS_NONE ){
			
			if ( !PluginCoreUtils.unwrap( download ).getDownloadState().isNetworkEnabled( AENetworkClassifier.AT_I2P )){

				plugin.log( "Netmix: activating " + download.getName() + " on demand" );

				setNetworkState( download, true );
			}
		}
	}
	
	@Override
	public void
	attributeEventOccurred(
		Download 			download, 
		TorrentAttribute 	attribute, 
		int 				event_type)
	{
		if ( attribute != ta_networks ){
			
			return;
		}
		
		int	existing_state = download.getIntAttribute( ta_mixstate );
		
		if ( existing_state == MS_CHANGING || existing_state == MS_MANUAL ){
			
			return;
		}
		
			// user is manually configuring networks, don't touch from now on
		
		download.setIntAttribute( ta_mixstate, MS_MANUAL );
	}
	
	private void
	setNetworkState(
		Download		download,
		boolean			enabled )
	{
		int	existing_state = download.getIntAttribute( ta_mixstate );
		
		if ( existing_state == MS_MANUAL ){
			
			return;
		}
		
		try{
			download.setIntAttribute( ta_mixstate, MS_CHANGING );
			
			PluginCoreUtils.unwrap( download ).getDownloadState().setNetworkEnabled( AENetworkClassifier.AT_I2P, enabled );
			
			if ( enabled ){
				
				download.setUserData( I2PHelperNetworkMixer.class, SystemTime.getMonotonousTime());
				
			}else{
				
				download.setUserData( I2PHelperNetworkMixer.class, null );
			}
		}finally{
			
			download.setIntAttribute( ta_mixstate, enabled?MS_ACTIVE:MS_NONE );
		}
	}
	
	protected void
	destroy()
	{
		synchronized( this ){
			
			destroyed = true;
			
			if ( check_pending != null ){
				
				check_pending.cancel();
				
				check_pending = null;
			}
		}
		
		if ( recheck_timer != null ){
			
			recheck_timer.cancel();
			
			recheck_timer = null;
		}
		
		plugin_interface.getDownloadManager().removeListener( this );
		
		DownloadManager dm = plugin_interface.getDownloadManager();
				
		for ( Download download: dm.getDownloads()){
			
			download.removeAttributeListener( this, ta_networks, DownloadAttributeListener.WRITTEN );
		}
	}
}
