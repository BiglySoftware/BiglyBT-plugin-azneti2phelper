/*
 * Created on 31-Jan-2005
 * Created by Paul Gardner
 * Copyright (C) 2004, 2005, 2006 Aelitis, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * AELITIS, SAS au capital de 46,603.30 euros
 * 8 Allee Lenotre, La Grille Royale, 78600 Le Mesnil le Roi, France.
 *
 */

package org.parg.azureus.plugins.networks.i2p.tracker;


import java.net.InetSocketAddress;
import java.net.URL;
import java.util.*;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import com.biglybt.core.config.COConfigurationManager;
import com.biglybt.core.download.DownloadManager;
import com.biglybt.core.download.DownloadManagerStats;
import com.biglybt.core.networkmanager.NetworkManager;
import com.biglybt.core.peer.PEPeerManager;
import com.biglybt.core.peer.PEPeerSource;
import com.biglybt.core.util.AEMonitor;
import com.biglybt.core.util.AENetworkClassifier;
import com.biglybt.core.util.AddressUtils;
import com.biglybt.core.util.BDecoder;
import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.SHA1Hasher;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimeFormatter;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.core.util.TimerEventPeriodic;
import com.biglybt.core.util.TorrentUtils;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadAnnounceResult;
import com.biglybt.pif.download.DownloadAnnounceResultPeer;
import com.biglybt.pif.download.DownloadAttributeListener;
import com.biglybt.pif.download.DownloadListener;
import com.biglybt.pif.download.DownloadManagerListener;
import com.biglybt.pif.download.DownloadScrapeResult;
import com.biglybt.pif.download.DownloadTrackerListener;
import com.biglybt.pif.peers.Peer;
import com.biglybt.pif.peers.PeerManager;
import com.biglybt.pif.torrent.Torrent;
import com.biglybt.pif.torrent.TorrentAttribute;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.utils.UTTimer;
import com.biglybt.pif.utils.UTTimerEvent;
import com.biglybt.pif.utils.UTTimerEventPerformer;
import com.biglybt.pifimpl.local.PluginCoreUtils;
import com.biglybt.plugin.dht.DHTPlugin;
import com.biglybt.plugin.dht.DHTPluginContact;
import com.biglybt.plugin.dht.DHTPluginOperationAdapter;
import com.biglybt.plugin.dht.DHTPluginOperationListener;
import com.biglybt.plugin.dht.DHTPluginValue;
import com.biglybt.ui.swt.Utils;
import com.biglybt.ui.swt.components.shell.ShellFactory;
import com.biglybt.ui.swt.views.stats.DHTOpsPanel;
import org.parg.azureus.plugins.networks.i2p.I2PHelperAdapter;
import org.parg.azureus.plugins.networks.i2p.I2PHelperDHT;
import org.parg.azureus.plugins.networks.i2p.I2PHelperDHTAdapter;
import org.parg.azureus.plugins.networks.i2p.I2PHelperDHTListener;
import org.parg.azureus.plugins.networks.i2p.I2PHelperPlugin.TorEndpoint;
import org.parg.azureus.plugins.networks.i2p.proxydht.TorProxyDHT;
import org.parg.azureus.plugins.networks.i2p.router.I2PHelperRouter;
import org.parg.azureus.plugins.networks.i2p.router.I2PHelperRouterDHT;
import org.parg.azureus.plugins.networks.i2p.vuzedht.DHTI2P;
import org.parg.azureus.plugins.networks.i2p.vuzedht.DHTTransportContactI2P;
import org.parg.azureus.plugins.networks.i2p.vuzedht.I2PHelperAZDHT;

import com.biglybt.core.dht.DHT;
import com.biglybt.core.dht.DHTOperationListener;
import com.biglybt.core.dht.control.DHTControlActivity;


/**
 * @author parg
 *
 */

public class 
I2PDHTTrackerPlugin 
	implements DownloadManagerListener, DownloadListener, DownloadAttributeListener, DownloadTrackerListener
{
	private static final int	ANNOUNCE_TIMEOUT			= 5*60*1000;
	private static final int	ANNOUNCE_DERIVED_TIMEOUT	= 60*1000;	// spend less time on these
	private static final int	SCRAPE_TIMEOUT				= 30*1000;
	private static final int	LOOKUP_TIMEOUT				= 2*60*1000;
	
	private static final int	ANNOUNCE_METADATA_RETRY		= 1*60*1000;
	
	private static final int	ANNOUNCE_MIN_DEFAULT		= 2*60*1000;
	private static final int	ANNOUNCE_MAX				= 45*60*1000;
	private static final int	ANNOUNCE_MAX_DERIVED_ONLY	= 30*60*1000;
	
	private static final int	INTERESTING_CHECK_PERIOD		= 4*60*60*1000;
	private static final int	INTERESTING_INIT_RAND_OURS		=    5*60*1000;
	private static final int	INTERESTING_INIT_MIN_OURS		=    2*60*1000;
	private static final int	INTERESTING_INIT_RAND_OTHERS	=   30*60*1000;
	private static final int	INTERESTING_INIT_MIN_OTHERS		=    5*60*1000;

	private static final int	INTERESTING_DHT_CHECK_PERIOD	= 1*60*60*1000;
	private static final int	INTERESTING_DHT_INIT_RAND		=    5*60*1000;
	private static final int	INTERESTING_DHT_INIT_MIN		=    2*60*1000;
	
	
	private static final int	INTERESTING_AVAIL_MAX		= 8;	// won't pub if more
	private static final int	INTERESTING_PUB_MAX_DEFAULT	= 30;	// limit on pubs
	
	private static final int	REG_TYPE_NONE			= 1;
	private static final int	REG_TYPE_FULL			= 2;
	private static final int	REG_TYPE_DERIVED		= 3;
	
	private static final int	LIMITED_TRACK_SIZE		= 16;
	
	private static final boolean	TRACK_NORMAL_DEFAULT	= true;
	private static final boolean	TRACK_LIMITED_DEFAULT	= true;
	
	private static final boolean	TEST_ALWAYS_TRACK		= false;
	
	public static final int	NUM_WANT			= 30;	// Limit to ensure replies fit in 1 packet

	private static final long	start_time = SystemTime.getCurrentTime();
	
	private static final Object	DL_DERIVED_METRIC_KEY		= new Object();
	private static final int	DL_DERIVED_MIN_TRACK		= 5;
	private static final int	DL_DERIVED_MAX_TRACK		= 20;
	private static final int	DIRECT_INJECT_PEER_MAX		= 5;
		
	private static final Object OUR_SCRAPE_RESULT_KEY = new Object();
	
	private static URL	DEFAULT_URL;
	
	static{
		try{
			DEFAULT_URL = new URL( "dht:" );
			
		}catch( Throwable e ){
			
			Debug.printStackTrace(e);
		}
	}
	
	private I2PHelperAdapter				adapter;
	private PluginInterface					plugin_interface;
	private I2PHelperRouter					router;
	private I2PDHTTrackerPluginListener		listener;
	
	private TorProxyDHT			tor_proxy_dht;
	
	private UTTimer				timer;
	private TorrentAttribute 	ta_networks;
	private TorrentAttribute 	ta_peer_sources;

	private Map<Download,Long>		interesting_downloads 	= new HashMap<Download,Long>();
	private int						interesting_published	= 0;
	private int						interesting_pub_max		= INTERESTING_PUB_MAX_DEFAULT;
	private Map<Download,int[]>		running_downloads 		= new HashMap<Download,int[]>();
	private Map<Download,int[]>		run_data_cache	 		= new HashMap<Download,int[]>();
	private Map<Download,RegistrationDetails>	registered_downloads 	= new HashMap<Download,RegistrationDetails>();
	
	private Map<Download,Boolean>	limited_online_tracking	= new HashMap<Download,Boolean>();
	private Map<Download,Long>		query_map			 	= new HashMap<Download,Long>();
	
	private Map<Download,Integer>	in_progress				= new HashMap<Download,Integer>();
	
		// external config to limit plugin op to pure decentralised only
	
	private boolean				track_only_decentralsed = COConfigurationManager.getBooleanParameter( "dhtplugin.track.only.decentralised", false );
	
	private BooleanParameter	track_normal_when_offline;
	private BooleanParameter	track_limited_when_online;
		
	private Map<Download,int[]>					scrape_injection_map = new WeakHashMap<Download,int[]>();
	
	private Random				random = new Random();
	private volatile boolean	is_running;				
	
	private AEMonitor			this_mon	= new AEMonitor( "DHTTrackerPlugin" );
				
	
	protected
	I2PDHTTrackerPlugin(
		I2PHelperAdapter				_adapter,
		I2PHelperRouter					_router,
		I2PDHTTrackerPluginListener		_listener )
	{	
		adapter				= _adapter;
		router				= _router;
		listener			= _listener;
		
		plugin_interface	= adapter.getPluginInterface();
		
		ta_networks 	= plugin_interface.getTorrentManager().getAttribute( TorrentAttribute.TA_NETWORKS );
		ta_peer_sources = plugin_interface.getTorrentManager().getAttribute( TorrentAttribute.TA_PEER_SOURCES );
	
		interesting_pub_max = plugin_interface.getPluginconfig().getPluginIntParameter( "dhttracker.presencepubmax", INTERESTING_PUB_MAX_DEFAULT );
		
		
		if ( !TRACK_NORMAL_DEFAULT ){
			// should be TRUE by default
			System.out.println( "**** DHT Tracker default set for testing purposes ****" );
		}
		
		initialise();
	}
	
	protected void
	initialise()
	{
		is_running	= true;
		
		plugin_interface.getDownloadManager().addListener( I2PDHTTrackerPlugin.this );
		
		tor_proxy_dht = adapter.getTorProxyDHT();
		
		timer = plugin_interface.getUtilities().createTimer("DHTI2P Tracker", true );
		
		timer.addPeriodicEvent(
			15000,
			new UTTimerEventPerformer()
			{
				private int	ticks;
				
				@Override
				public void
				perform(
					UTTimerEvent event) 
				{
					if ( !is_running ){
						
						return;
					}
					
					ticks++;
					
					processRegistrations( ticks%8==0 );
					
					if ( ticks == 2 || ticks%4==0 ){
						
						processNonRegistrations();
					}
				}
			});
	}
	
	protected void
	unload()
	{
		is_running = false;
		
		if ( timer != null ){
		
			timer.destroy();
			
			timer = null;
		}
		
		com.biglybt.pif.download.DownloadManager dm = plugin_interface.getDownloadManager();
		
		dm.removeListener( I2PDHTTrackerPlugin.this );
		
		for ( Download download: dm.getDownloads()){
			
			download.removeAttributeListener(I2PDHTTrackerPlugin.this, ta_networks, DownloadAttributeListener.WRITTEN);
			download.removeAttributeListener(I2PDHTTrackerPlugin.this, ta_peer_sources, DownloadAttributeListener.WRITTEN);
			
			download.removeTrackerListener( I2PDHTTrackerPlugin.this );
			
			download.removeListener( I2PDHTTrackerPlugin.this );
		}
	}
	
	@Override
	public void
	downloadAdded(
		final Download	download )
	{
		Torrent	torrent = download.getTorrent();

		boolean	is_decentralised = false;
		
		if ( torrent != null ){
			
			is_decentralised = TorrentUtils.isDecentralised( torrent.getAnnounceURL());
		}
		
			// bail on our low noise ones, these don't require decentralised tracking unless that's what they are
	
		if ( download.getFlag( Download.FLAG_LOW_NOISE ) && !is_decentralised ){
		
			return;
		}
		
		if ( track_only_decentralsed ){
		
			if ( torrent != null ){
				
				if ( !is_decentralised ){
					
					return;
				}
			}
		}
	
		if ( is_running ){
			
			String[]	networks = download.getListAttribute( ta_networks );
						
			if ( torrent != null && networks != null ){
				
				boolean	i2p_net = false;
				
				for (int i=0;i<networks.length;i++){
					
					if ( networks[i].equalsIgnoreCase( "I2P" )){
							
						i2p_net	= true;
						
						break;
					}
				}
				
				if ( i2p_net && !TorrentUtils.isReallyPrivate( PluginCoreUtils.unwrap( torrent ))){
	
					boolean	our_download =  torrent.wasCreatedByUs();
	
					long	delay;
					
					if ( our_download ){
						
						if ( download.getCreationTime() > start_time ){
							
							delay = 0;
						
						}else{
						
							delay = plugin_interface.getUtilities().getCurrentSystemTime() + 
									INTERESTING_INIT_MIN_OURS + 
									random.nextInt( INTERESTING_INIT_RAND_OURS );
	
						}
					}else{
						
						int	min;
						int	rand;
						
						if ( TorrentUtils.isDecentralised( torrent.getAnnounceURL())){
							
							min		= INTERESTING_DHT_INIT_MIN;	
							rand	= INTERESTING_DHT_INIT_RAND;

						}else{
							
							min		= INTERESTING_INIT_MIN_OTHERS;	
							rand	= INTERESTING_INIT_RAND_OTHERS;
						}
						
						delay = plugin_interface.getUtilities().getCurrentSystemTime() + 
									min + random.nextInt( rand );
					}
					
					try{
						this_mon.enter();
						
						setInteresting( download, new Long( delay ));
						
					}finally{
						
						this_mon.exit();
					}
				}
			}
			
			download.addAttributeListener(I2PDHTTrackerPlugin.this, ta_networks, DownloadAttributeListener.WRITTEN);
			download.addAttributeListener(I2PDHTTrackerPlugin.this, ta_peer_sources, DownloadAttributeListener.WRITTEN);
			
			download.addTrackerListener( I2PDHTTrackerPlugin.this );
			
			download.addListener( I2PDHTTrackerPlugin.this );
			
			checkDownloadForRegistration( download, true );
		}
	}
	
	@Override
	public void
	downloadRemoved(
		Download	download )
	{
		if ( is_running ){
			
			download.removeTrackerListener( I2PDHTTrackerPlugin.this );
	
			download.removeListener( I2PDHTTrackerPlugin.this );
			
			try{
				this_mon.enter();
	
				interesting_downloads.remove( download );
				
				running_downloads.remove( download );
				
				run_data_cache.remove( download );
				
				limited_online_tracking.remove( download );
				
			}finally{
				
				this_mon.exit();
			}
		}else{
			
		}
	}
	
	@Override
	public void
	attributeEventOccurred(
		Download 			download, 
		TorrentAttribute 	attr, 
		int 				event_type) 
	{
		checkDownloadForRegistration(download, false);
	}
	
	@Override
	public void
	scrapeResult(
		DownloadScrapeResult	result )
	{
		checkDownloadForRegistration( result.getDownload(), false );
	}
	
	@Override
	public void
	announceResult(
		DownloadAnnounceResult	result )
	{
		checkDownloadForRegistration( result.getDownload(), false );
	}
	
	
	protected void
	checkDownloadForRegistration(
		Download		download,
		boolean			first_time )
	{
		if ( download == null ){
			
			return;
		}
		
		boolean	skip_log = false;
		
		int	state = download.getState();
			
		int	register_type	= REG_TYPE_NONE;
		
		String	register_reason;
		
		Random	random = new Random();
			/*
			 * Queued downloads are removed from the set to consider as we now have the "presence store"
			 * mechanism to ensure that there are a number of peers out there to provide torrent download
			 * if required. This has been done to avoid the large number of registrations that users with
			 * large numbers of queued torrents were getting.
			 */
		
		if ( 	state == Download.ST_DOWNLOADING 	||
				state == Download.ST_SEEDING 		||
				// state == Download.ST_QUEUED 		||	
				download.isPaused()){	// pause is a transitory state, don't dereg
						
			String[]	networks = download.getListAttribute( ta_networks );
			
			Torrent	torrent = download.getTorrent();
			
			if ( torrent != null && networks != null ){
				
				boolean	i2p_net = false;
				
				for (int i=0;i<networks.length;i++){
					
					if ( networks[i].equalsIgnoreCase( "I2P" )){
							
						i2p_net	= true;
						
						break;
					}
				}
				
				if ( i2p_net && !TorrentUtils.isReallyPrivate( PluginCoreUtils.unwrap( torrent ))){
					
					if ( torrent.isDecentralised()){
						
							// peer source not relevant for decentralised torrents
						
						register_type	= REG_TYPE_FULL;
						
						register_reason = "decentralised";
							
					}else{
						
						if ( torrent.isDecentralisedBackupEnabled() || TEST_ALWAYS_TRACK ){
								
							String[]	sources = download.getListAttribute( ta_peer_sources );
	
							boolean	ok = false;
							
							if ( sources != null ){
								
								for (int i=0;i<sources.length;i++){
									
									if ( sources[i].equalsIgnoreCase( "DHT")){
										
										ok	= true;
										
										break;
									}
								}
							}
							
							if ( !( ok || TEST_ALWAYS_TRACK )){
											
								register_reason = "decentralised peer source disabled";
								
							}else{
									// this will always be true since change to exclude queued...
								
								boolean	is_active = 
											state == Download.ST_DOWNLOADING ||
											state == Download.ST_SEEDING ||
											download.isPaused();

								if ( is_active ){
									
									register_type = REG_TYPE_DERIVED;
								}
								
								if( torrent.isDecentralisedBackupRequested() || TEST_ALWAYS_TRACK ){
									
									register_type	= REG_TYPE_FULL;
									
									register_reason = TEST_ALWAYS_TRACK?"testing always track":"torrent requests decentralised tracking";
									
								}else if ( false && track_normal_when_offline.getValue()){
									
										// TODO:
										// only track if torrent's tracker is not available
																
									if ( is_active ){
										
										DownloadAnnounceResult result = download.getLastAnnounceResult();
										
										if (	result == null ||
												result.getResponseType() == DownloadAnnounceResult.RT_ERROR ||
												TorrentUtils.isDecentralised(result.getURL())){
											
											register_type	= REG_TYPE_FULL;
											
											register_reason = "tracker unavailable (announce)";
											
										}else{	
											
											register_reason = "tracker available (announce: " + result.getURL() + ")";								
										}
									}else{
										
										DownloadScrapeResult result = download.getLastScrapeResult();
										
										if (	result == null || 
												result.getResponseType() == DownloadScrapeResult.RT_ERROR ||
												TorrentUtils.isDecentralised(result.getURL())){
											
											register_type	= REG_TYPE_FULL;
											
											register_reason = "tracker unavailable (scrape)";
											
										}else{
											
											register_reason = "tracker available (scrape: " + result.getURL() + ")";								
										}
									}
									
									if ( register_type != REG_TYPE_FULL && track_limited_when_online.getValue()){
										
										Boolean	existing = (Boolean)limited_online_tracking.get( download );
										
										boolean	track_it = false;
										
										if ( existing != null ){
											
											track_it = existing.booleanValue();
											
										}else{
											
											DownloadScrapeResult result = download.getLastScrapeResult();
											
											if (	result != null&& 
													result.getResponseType() == DownloadScrapeResult.RT_SUCCESS ){
												
												int	seeds 		= result.getSeedCount();
												int leechers	= result.getNonSeedCount();
												
												int	swarm_size = seeds + leechers;
																								
												if ( swarm_size <= LIMITED_TRACK_SIZE ){
													
													track_it = true;
													
												}else{
													
													track_it = random.nextInt( swarm_size ) < LIMITED_TRACK_SIZE;
												}
												
												if ( track_it ){
													
													limited_online_tracking.put( download, new Boolean( track_it ));
												}
											}
										}
										
										if( track_it ){
											
											register_type	= REG_TYPE_FULL;
											
											register_reason = "limited online tracking";
										}
									}
								}else{
									register_type	= REG_TYPE_FULL;
									
									register_reason = "peer source enabled";
								}
							}
						}else{
							
							register_reason = "decentralised backup disabled for the torrent";
						}
					}
				}else{
					
					register_reason = "network not compatible";
				}
			}else{
				
				register_reason = "torrent is broken";
			}
			
			if ( register_type == REG_TYPE_DERIVED ){
								
				if ( register_reason.length() == 0 ){
					
					register_reason = "derived";
					
				}else{
					
					register_reason = "derived (overriding ' " + register_reason + "')";
				}
			}
		}else if ( 	state == Download.ST_STOPPED ||
					state == Download.ST_ERROR ){
			
			register_reason	= "not running";
			
			skip_log	= true;
			
		}else if ( 	state == Download.ST_QUEUED ){

				// leave in whatever state it current is (reg or not reg) to avoid thrashing
				// registrations when seeding rules are start/queueing downloads
			
			register_reason	= "";
			
		}else{
			
			register_reason	= "";
		}
		
		if ( register_reason.length() > 0 ){
			
			try{
				this_mon.enter();
	
				int[] run_data = running_downloads.get( download );
			
				if ( register_type != REG_TYPE_NONE ){
				
					if ( run_data == null ){
						
						log( download,	"Monitoring '" + download.getName() + "': " + register_reason);
						
						int[] cache = run_data_cache.remove( download );
						
						if ( cache == null ){
						
							running_downloads.put( download, new int[]{ register_type, 0, 0, 0, 0 });
							
						}else{
							
							cache[0] = register_type;
							
							running_downloads.put( download, cache );
						}
						
						query_map.put( download, new Long( SystemTime.getCurrentTime()));
						
					}else{
						
						Integer	existing_type = run_data[0];

						if ( 	existing_type.intValue() == REG_TYPE_DERIVED &&
								register_type == REG_TYPE_FULL ){
						
								// upgrade
						
							run_data[0] = register_type;
						}
					}
				}else{
					
					if ( run_data  != null ){
						
						if ( !skip_log ){
							
							log( download, "Not monitoring: "	+ register_reason);
						}
	
						running_downloads.remove( download );
						
						run_data_cache.put( download, run_data );
						
							// add back to interesting downloads for monitoring
						
						setInteresting( 
								download,
								new Long( 	plugin_interface.getUtilities().getCurrentSystemTime() + 
											INTERESTING_INIT_MIN_OTHERS ));

					}else{
						
						if ( first_time && !skip_log ){
							
							log( download, "Not monitoring: "	+ register_reason);
						}
					}
				}
			}finally{
				
				this_mon.exit();
			}
		}
	}
	
	protected void
	processRegistrations(
		boolean		full_processing )
	{
	    String override_ip		= null;	
  	    String 	value_to_put 	= "";
  	    int		udp_port 		= 0;
  	    
		putDetails	put_details = new putDetails( value_to_put, override_ip, 6881, udp_port );
		
		ArrayList<Download>	rds;
		
		try{
			this_mon.enter();

			rds = new ArrayList<Download>(running_downloads.keySet());
			
		}finally{
			
			this_mon.exit();
		}

		long	 now = SystemTime.getCurrentTime();
		
		
		if ( full_processing ){
			
			Iterator<Download>	rds_it = rds.iterator();
			
			List<Object[]> interesting = new ArrayList<Object[]>();
			
			while( rds_it.hasNext()){
				
				Download	dl = rds_it.next();
				
				int	reg_type = REG_TYPE_NONE;
				
				try{ 
					this_mon.enter();
				
					int[] run_data = running_downloads.get( dl );
					
					if ( run_data != null ){
						
						reg_type = run_data[0];
					}
				}finally{
					
					this_mon.exit();
				}
					
		  		if ( reg_type == REG_TYPE_NONE ){

		  			continue;
		  		}
		  		
		  		long metric = getDerivedTrackMetric( dl );
		  		
		  		interesting.add( new Object[]{ dl, new Long( metric )} );
			}
			
			Collections.sort(
				interesting,
				new Comparator<Object[]>()
				{
					@Override
					public int
					compare(
						Object[] entry1, 
						Object[] entry2) 
					{						
						long	res = ((Long)entry2[1]).longValue() - ((Long)entry1[1]).longValue();
						
						if( res < 0 ){
							
							return( -1 );
							
						}else if ( res > 0 ){
							
							return( 1 );
							
						}else{
							
							return( 0 );
						}
					}
				});
			
			Iterator<Object[]> it	= interesting.iterator();
			
			int	num = 0;
			
			while( it.hasNext()){
				
				Object[] entry = it.next();
				
				Download	dl 		= (Download)entry[0];
				long		metric	= ((Long)entry[1]).longValue();
				
				num++;
				
				if ( metric > 0 ){
					
					if ( num <= DL_DERIVED_MIN_TRACK ){
						
							// leave as is
						
					}else if ( num <= DL_DERIVED_MAX_TRACK ){
						
							// scale metric between limits
						
						metric = ( metric * ( DL_DERIVED_MAX_TRACK - num )) / ( DL_DERIVED_MAX_TRACK - DL_DERIVED_MIN_TRACK );
						
					}else{
						
						metric = 0;
					}
				}
				
				if ( metric > 0 ){
					
					dl.setUserData( DL_DERIVED_METRIC_KEY, new Long( metric ));
					
				}else{
					
					dl.setUserData( DL_DERIVED_METRIC_KEY, null );
				}
			}
		}
		
		Iterator<Download>	rds_it = rds.iterator();
		
			// first off do any puts
		
		while( rds_it.hasNext()){
			
			Download	dl = rds_it.next();
			
			int	reg_type = REG_TYPE_NONE;
			
			try{ 
				this_mon.enter();
			
				int[] run_data = running_downloads.get( dl );
				
				if ( run_data != null ){
					
					reg_type = run_data[0];
				}
			}finally{
				
				this_mon.exit();
			}
				
	  		if ( reg_type == REG_TYPE_NONE ){

	  			continue;
	  		}
	  		
			byte	flags = (byte)( isComplete( dl )?DHT.FLAG_SEEDING:DHT.FLAG_DOWNLOADING );
			
			RegistrationDetails	registration = (RegistrationDetails)registered_downloads.get( dl );
			
			boolean	do_it = false;
			
			if ( registration == null ){
				
				log( dl, "Registering download as " + (flags == DHT.FLAG_SEEDING?"Seeding":"Downloading"));

				registration = new RegistrationDetails( dl, reg_type, put_details, flags );
				
				registered_downloads.put( dl, registration );

				do_it = true;
				
			}else{

				boolean	targets_changed = false;
				
				if ( full_processing ){
					
					targets_changed = registration.updateTargets( dl, reg_type );
				}
				
				if (	targets_changed ||
						registration.getFlags() != flags ||
						!registration.getPutDetails().sameAs( put_details )){
				
					log( dl,(registration==null?"Registering":"Re-registering") + " download as " + (flags == DHT.FLAG_SEEDING?"Seeding":"Downloading"));
					
					registration.update( put_details, flags );
					
					do_it = true;
				}
			} 
			
			if ( do_it ){
				
				try{ 
					this_mon.enter();

					query_map.put( dl, new Long( now ));

				}finally{
					
					this_mon.exit();
				}
					  					  						  	    
				trackerPut( dl, registration );
			}
		}
		
			// second any removals
		
		Iterator<Map.Entry<Download,RegistrationDetails>> rd_it = registered_downloads.entrySet().iterator();
		
		while( rd_it.hasNext()){
			
			Map.Entry<Download,RegistrationDetails>	entry = rd_it.next();
			
			final Download	dl = entry.getKey();

			boolean	unregister;
			
			try{ 
				this_mon.enter();

				unregister = !running_downloads.containsKey( dl );
				
			}finally{
				
				this_mon.exit();
			}
			
			if ( unregister ){
				
				log( dl, "Unregistering download" );
								
				rd_it.remove();
				
				try{
					this_mon.enter();

					query_map.remove( dl );
					
				}finally{
					
					this_mon.exit();
				}
				
				trackerRemove( dl, entry.getValue());
			}
		}
		
			// lastly gets
		
		rds_it = rds.iterator();
		
		while( rds_it.hasNext()){
			
			final Download	dl = (Download)rds_it.next();
			
			Long	next_time;
			
			try{
				this_mon.enter();
	
				next_time = (Long)query_map.get( dl );
				
			}finally{
				
				this_mon.exit();
			}
			
			if ( next_time != null && now >= next_time.longValue()){
			
				int	reg_type = REG_TYPE_NONE;
				
				try{
					this_mon.enter();
		
					query_map.remove( dl );
					
					int[] run_data = running_downloads.get( dl );
					
					if ( run_data != null ){
						
						reg_type = run_data[0];
					}
				}finally{
					
					this_mon.exit();
				}
				
				final long	start = SystemTime.getCurrentTime();
					
					// if we're already connected to > NUM_WANT peers then don't bother with the main announce
				
				PeerManager	pm = dl.getPeerManager();
				
					// don't query if this download already has an active DHT operation
				
				boolean	skip	= isActive( dl ) || reg_type == REG_TYPE_NONE;
				
				if ( skip ){
					
					log( dl, "Deferring announce as activity outstanding" );
				}
				
				RegistrationDetails	registration = (RegistrationDetails)registered_downloads.get( dl );

				if ( registration == null ){
					
					Debug.out( "Inconsistent, registration should be non-null" );
					
					continue;
				}
				
				boolean	derived_only = false;
				
				if ( pm != null && !skip ){
					
					int	con = pm.getStats().getConnectedLeechers() + pm.getStats().getConnectedSeeds();
				
					derived_only = con >= NUM_WANT;
				}
				
				if ( !skip ){
					
					skip = trackerGet( dl, registration, derived_only ) == 0;
					
				}
				
					// if we didn't kick off a get then we have to reschedule here as normally
					// the get operation will do the rescheduling when it receives a result
				
				if ( skip ){
					
					try{
						this_mon.enter();
					
						if ( running_downloads.containsKey( dl )){
							
								// use "min" here as we're just deferring it
							
							query_map.put( dl, new Long( start + ANNOUNCE_MIN_DEFAULT ));
						}
						
					}finally{
						
						this_mon.exit();
					}
				}
			}
		}
	}
	
	protected long
	getDerivedTrackMetric(
		Download		download )
	{
			// metric between -100 and + 100. Note that all -ve mean 'don't do it'
			// they're just indicating different reasons 
	
		Torrent t = download.getTorrent();
		
		if ( t == null ){
			
			return( -100 );
		}
		
		if ( t.getSize() < 10*1024*1024 ){
			
			return( -99 );
		}
		
		DownloadAnnounceResult announce = download.getLastAnnounceResult();
		
		if ( 	announce == null ||
				announce.getResponseType() != DownloadAnnounceResult.RT_SUCCESS ){			
			
			return( -98 );
		}
		
		DownloadScrapeResult scrape = download.getLastScrapeResult();
		
		if ( 	scrape == null ||
				scrape.getResponseType() != DownloadScrapeResult.RT_SUCCESS ){
			
			return( -97 );
		}
		
		int leechers 	= scrape.getNonSeedCount();
		// int seeds		= scrape.getSeedCount();
		
		int	total = leechers;	// parg - changed to just use leecher count rather than seeds+leechers
		
		if ( total >= 2000 ){
			
			return( 100 );
			
		}else if ( total <= 200 ){
			
			return( 0 );
			
		}else{
		
			return( ( total - 200 ) / 4 );
		}
	}
	
	private static final Object AZ_HASH_KEY = new Object();
	
	private byte[]
	getAZHash(
		Download		download,
		byte[]			hash )
	{
		byte[] result = (byte[])download.getUserData( AZ_HASH_KEY );
		
		if ( result == null ){
			
			SHA1Hasher hasher = new SHA1Hasher();
			
			hasher.update( hash );
			
			hasher.update( "I2PDHTTrackerPlugin::AZDest".getBytes( Constants.UTF_8 ));
			
			result = hasher.getDigest();
			
			download.setUserData( AZ_HASH_KEY, result );
		}

		return( result );
	}
	
	protected void
	trackerPut(
		final Download			download,
		RegistrationDetails		details )
	{
		final 	long	start = SystemTime.getCurrentTime();
		 	    
		trackerTarget[] targets = details.getTargets( true );
		
		byte flags = details.getFlags();
		
		boolean	listener_informed = false;
		
		for (int i=0;i<targets.length;i++){
			
			final trackerTarget target = targets[i];
			
		 	    // don't let a put block an announce as we don't want to be waiting for 
		  	    // this at start of day to get a torrent running
		  	    
		  	    // increaseActive( dl );
	 
			String	encoded = details.getPutDetails().getEncoded();
						
			if ( download.getFlag( Download.FLAG_METADATA_DOWNLOAD )){
				
				log( download, "Registration of '" + target.getDesc() + "' skipped as metadata download");

			}else{
				
				try{
					final I2PHelperRouterDHT rdht = router.selectDHT( download );
					
					I2PHelperDHT dht = rdht.getDHT(true);
				
					if ( listener != null && !listener_informed ){
						
						listener_informed = true;
						
						try{
							listener.trackingStarted( download, dht );
							
						}catch( Throwable e ){
							
						}
					}
					
					byte[] hash = target.getHash();
					
					dht.put( 
						hash,
						"Tracker reg of '" + download.getName() + "'" + target.getDesc() + " -> " + encoded,
						flags,
						new I2PHelperDHTAdapter()
						{
							@Override
							public void
							complete(
								boolean		timeout )
							{
								if ( target.getType() == REG_TYPE_FULL ){
									
									log( 	download,
											"Registration of '" + target.getDesc(rdht) + "' completed (elapsed="	+ TimeFormatter.formatColonMillis((SystemTime.getCurrentTime() - start)) + ")");
								}
								
									// decreaseActive( dl );
							}
						});
										
					String tor_host = router.getPlugin().getTorEndpoint( dht.getDHTIndex()).getHost();
					
					String[]	networks = download.getListAttribute( ta_networks );
					
					boolean has_tor = false;
					
					for ( String net: networks ){
						
						if ( net == AENetworkClassifier.AT_TOR ){
							
							has_tor = true;
						}
					}
					
					if ( tor_host != null && has_tor ){
					
						int pos = tor_host.lastIndexOf( "." );
						
						if ( pos != -1 ){
							
							tor_host = tor_host.substring( 0, pos );
						}
						
						Map map = new HashMap();
						
						byte[] thb = tor_host.getBytes( Constants.UTF_8 );
						
						for ( int j=0;j<thb.length;j++){
							
							thb[j] ^= hash[j%hash.length];
						}
						
						map.put( "th", thb );
												
						int tor_flags = 0;
						
						if ( NetworkManager.REQUIRE_CRYPTO_HANDSHAKE ){
							
							tor_flags |= 0x01;
						}
						
						boolean is_seed = isComplete( download );

						if ( is_seed ){
							
							tor_flags |= 0x02;
						}
						
						if ( tor_flags != 0 ){
							
							map.put( "f", tor_flags );
						}
						
						byte[] az_encoded = BEncoder.encode( map );
						
						dht.getHelperAZDHT().put( 
							getAZHash( download, hash ),
							"Tracker AZ reg of '" + download.getName() + "'" + target.getDesc() + " -> " + encoded,
							az_encoded,
							flags,
							false,
							new DHTPluginOperationAdapter(){
																						
								@Override
								public void complete(byte[] key, boolean timeout_occurred){
									if ( target.getType() == REG_TYPE_FULL ){
										
										log( 	download,
												"AZ Registration of '" + target.getDesc(rdht) + "' completed (elapsed="	+ TimeFormatter.formatColonMillis((SystemTime.getCurrentTime() - start)) + ")");
									}
								}
							});
						
						if ( rdht.getDHTIndex() == I2PHelperRouter.DHT_NON_MIX && tor_proxy_dht != null ){
							
							tor_proxy_dht.proxyTrackerAnnounce( hash, is_seed );
						}
					}					
				}catch( Throwable e ){					
				}
			}
		}
	}
	
	protected int
	trackerGet(
		final Download					download,
		final RegistrationDetails		details,
		final boolean					derived_only )
	{
		trackerTarget[] targets = details.getTargets( false );
		
		final long[]	max_retry = { 0 };
		
		int	num_done = 0;
		
		for (int i=0;i<targets.length;i++){
			
			final trackerTarget target = targets[i];
	
			int	target_type = target.getType();
			
			if ( target_type == REG_TYPE_FULL && derived_only ){
				
				continue;
			}
			
			increaseActive( download );
			
			num_done++;
			
			try{
				final I2PHelperRouterDHT rdht = router.selectDHT( download );

				I2PHelperDHT dht = rdht.getDHT(true);
				
				byte[] hash = target.getHash();
				
				boolean is_complete = isComplete( download );
				
				
				String tor_host = router.getPlugin().getTorEndpoint( dht.getDHTIndex()).getHost();
				
				String[]	networks = download.getListAttribute( ta_networks );
				
				boolean has_tor = false;
				
				for ( String net: networks ){
					
					if ( net == AENetworkClassifier.AT_TOR ){
						
						has_tor = true;
					}
				}

				boolean	secondary_get = tor_host != null && has_tor;
				
				boolean proxy_get = rdht.getDHTIndex() == I2PHelperRouter.DHT_NON_MIX && tor_proxy_dht != null;
				
				int complete_count = 1;
				
				if ( secondary_get ){
					
					complete_count++;
				}				
				
				if ( proxy_get ){
					
					complete_count++;
				}
				
				GetAdapter get_adapter = new GetAdapter( rdht, download, details, target, derived_only, max_retry, complete_count );
				
				dht.get(
						hash, 
						"Tracker announce for '" + download.getName() + "'" + target.getDesc(),
						(byte)( is_complete?DHT.FLAG_SEEDING:DHT.FLAG_DOWNLOADING),
						NUM_WANT, 
						target_type==REG_TYPE_FULL?ANNOUNCE_TIMEOUT:ANNOUNCE_DERIVED_TIMEOUT,
						get_adapter );
				
				
				
				if ( secondary_get ){
				
					dht.getHelperAZDHT().get(
						getAZHash(download, hash),
						"Tracker AZ announce for '" + download.getName() + "'" + target.getDesc(),
						is_complete?DHTPlugin.FLAG_SEEDING:DHTPlugin.FLAG_DOWNLOADING,
						NUM_WANT,
						target_type==REG_TYPE_FULL?ANNOUNCE_TIMEOUT:ANNOUNCE_DERIVED_TIMEOUT,
						false, false,
						get_adapter );
					
					if ( proxy_get ){
						
						tor_proxy_dht.proxyTrackerGet( 
							hash, 
							is_complete, 
							NUM_WANT,
							get_adapter );
					}
				}
				
			}catch( Throwable e ){	
				
				decreaseActive(download);
			}
		}
		
		return( num_done );
	}
	
	public void
	trackerGet(
		String					reason,
		byte[]					torrent_hash,
		Map<String,Object>		options,
		I2PHelperDHTAdapter		listener )
	{
		try{
			I2PHelperRouterDHT rdht = router.selectDHT( options );
			
			rdht.getDHT(true).get(
					torrent_hash, 
					reason,
					(byte)DHT.FLAG_DOWNLOADING,
					NUM_WANT, 
					ANNOUNCE_DERIVED_TIMEOUT,
					//false, false,
					listener );
			
		}catch( Throwable e ){
			
			listener.complete( false );
		}
	}
	
	public void
	trackerGet(
		final String							reason,
		final byte[]							torrent_hash,
		final Map<String,Object>				options,
		final I2PHelperAZDHT					az_dht,
		final I2PHelperDHTAdapter				listener )
	{
		try{
			final DHT dht = az_dht.getBaseDHT();
			
			Utils.execSWTThread(
				new Runnable()
				{
					private TimerEventPeriodic timer;
					
					@Override
					public void
					run()
					{
						Composite parent = (Composite)options.get( "ui_composite" );
												
						Shell shell = null;
						
						if ( parent == null ){
							
							shell = ShellFactory.createMainShell( SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MIN | SWT.MAX );
								
							parent	= shell;
							
						}else if ( parent.isDisposed()){
							
							listener.complete( false );
							
							return;
						}

						parent.setLayout( new GridLayout());
						
						Composite panel_comp = new Composite( parent, SWT.NULL );
						GridData grid_data = new GridData( GridData.FILL_BOTH );
						panel_comp.setLayoutData( grid_data );
						
						panel_comp.setLayout(new FillLayout());
						
						final DHTOpsPanel panel = new DHTOpsPanel( panel_comp );
						
						panel.setFilter(
							new DHTOpsPanel.ActivityFilter() {
								
								@Override
								public boolean 
								accept(
									DHTControlActivity activity) 
								{
									return( Arrays.equals( activity.getTarget(),torrent_hash ));
								}
							});
						
						panel.setMinimumSlots( 5 );
						
						panel.setScaleAndRotation(0, 1000, -1000, 1000, Math.PI/2 );
												
						final Text text = new Text( parent, SWT.NULL );
						grid_data = new GridData( GridData.FILL_HORIZONTAL );
						text.setLayoutData( grid_data );
						
						if ( shell != null ){
							
							shell.setSize( 400, 400 );
							
							shell.open();
							
						}else{
							
							parent.layout( true );
						}
												
						panel.refreshView( dht );
						
						I2PHelperDHTListener	listener_wrapper =
							new I2PHelperDHTListener()
							{
								private boolean	complete;
								private int		found;
								
								@Override
								public void
								searching(
									final String		host )
								{
									listener.searching(host);
									
									update( "[" + found + "] Searching " + host.substring( 0, 20 ) + "...", false );
								}
								
								@Override
								public void
								valueRead(
									DHTTransportContactI2P		contact,
									String						host,
									int							contact_state )
								{
									listener.valueRead(contact, host, contact_state);
									
									synchronized( this ){
										
										found++;
									}
									
									update( "Found " + host, false );
								}
								
								@Override
								public void
								complete(
									boolean		timeout )
								{
									listener.complete(timeout);
									
									update( "", false );
								}
								
								private void
								update(
									final String 	str,
									boolean			is_done )
								{
									synchronized( this ){
										
										if ( complete ){
											
											return;
										}
										
										complete = is_done;
									}
									
									Utils.execSWTThread(
										new Runnable()
										{
											@Override
											public void
											run()
											{
												if ( text.isDisposed()){
												
													synchronized( this ){
														
														complete = true;
													}
												}else{
													
													text.setText( str );
												}
											}
										});
								}
							};
							
						DHTOperationListener awesome_listener =	new DHTI2P.GetCacheEntry( listener_wrapper );
						
						dht.get(
								torrent_hash, 
								reason,
								DHT.FLAG_DOWNLOADING,
								NUM_WANT, 
								LOOKUP_TIMEOUT,
								false, 
								true,
								awesome_listener );
						
						final Composite f_parent = parent;
						
						timer = 
							SimpleTimer.addPeriodicEvent(
								"dhtops",
								250,
								new TimerEventPerformer() {
									
									@Override
									public void 
									perform(
										TimerEvent event) 
									{
										if ( f_parent.isDisposed()){
											
											timer.cancel();
											
										}else{
											
											Utils.execSWTThread(
												new Runnable()
												{
													@Override
													public void
													run()
													{
														panel.refresh();
													}
												});
										}
									}
								});
						
					}
				});
			
			
		}catch( Throwable e ){
			
			listener.complete( false );
		}
	}
	
	protected boolean
	isComplete(
		Download	download )
	{
		if ( Constants.DOWNLOAD_SOURCES_PRETEND_COMPLETE ){
			
			return( true );
		}
		
		boolean	is_complete = download.isComplete();
		
		if ( is_complete ){
			
			PeerManager pm = download.getPeerManager();
						
			if ( pm != null ){
			
				PEPeerManager core_pm = PluginCoreUtils.unwrap( pm );
				
				if ( core_pm != null && core_pm.getHiddenBytes() > 0 ){
					
					is_complete = false;
				}
			}
		}
		
		return( is_complete );
	}
	
	protected void
	trackerRemove(
		final Download			download,
		RegistrationDetails		details )
	{
		if ( download.getFlag( Download.FLAG_METADATA_DOWNLOAD )){
			
			return;
		}
		
		final 	long	start = SystemTime.getCurrentTime();
		
		trackerTarget[] targets = details.getTargets( true );
		
		try{
			final I2PHelperRouterDHT rdht = router.selectDHT( download );
			
			I2PHelperDHT dht = rdht.getDHT(true);
			
			if ( listener != null ){
				
				try{
					listener.trackingStopped( download, dht );
					
				}catch( Throwable e ){
					
				}
			}
						
			for (int i=0;i<targets.length;i++){
				
				final trackerTarget target = targets[i];
			
				byte[] hash = target.getHash();

				if ( dht.hasLocalKey( hash )){
					
					String tor_host = router.getPlugin().getTorEndpoint( dht.getDHTIndex()).getHost();
					
					String[]	networks = download.getListAttribute( ta_networks );
					
					boolean has_tor = false;
					
					for ( String net: networks ){
						
						if ( net == AENetworkClassifier.AT_TOR ){
							
							has_tor = true;
						}
					}

					boolean	secondary_remove = tor_host != null && has_tor;
					
					increaseActive( download );
					
					dht.remove( 
							hash,
							"Tracker dereg of '" + download.getName() + "'" + target.getDesc(),
							new I2PHelperDHTAdapter()
							{
								@Override
								public void
								complete(
									boolean	timeout_occurred )
								{
									if ( target.getType() == REG_TYPE_FULL ){
		
										log( 	download,
												"Unregistration of '" + target.getDesc(rdht) + "' completed (elapsed="
													+ TimeFormatter.formatColonMillis(SystemTime.getCurrentTime() - start) + ")");
									}
									
									decreaseActive( download );
								}
							});
					
					if ( secondary_remove ){
						
						dht.getHelperAZDHT().remove(
								getAZHash(download, hash),
								"Tracker AZ dereg of '" + download.getName() + "'" + target.getDesc(),
								new DHTPluginOperationAdapter());
						
						if ( rdht.getDHTIndex() == I2PHelperRouter.DHT_NON_MIX && tor_proxy_dht != null ){
							
							tor_proxy_dht.proxyTrackerRemove( hash );
						}
					}
				}
			}
		}catch( Throwable e ){
			
		}
	}

	protected void
	trackerRemove(
		final Download			download,
		final trackerTarget 	target )
	{
		if ( download.getFlag( Download.FLAG_METADATA_DOWNLOAD )){
			
			return;
		}
		
		try{
			final 	long	start = SystemTime.getCurrentTime();
			
			final I2PHelperRouterDHT rdht = router.selectDHT( download );

			I2PHelperDHT dht = rdht.getDHT(true);
	
			if ( dht.hasLocalKey( target.getHash())){
				
				increaseActive( download );
				
				dht.remove( 
						target.getHash(),
						"Tracker dereg of '" + download.getName() + "'" + target.getDesc(),
						new I2PHelperDHTAdapter()
						{
							@Override
							public void
							complete(
								boolean	timeout_occurred )
							{
								if ( target.getType() == REG_TYPE_FULL ){
	
									log( 	download,
											"Unregistration of '" + target.getDesc(rdht) + "' completed (elapsed="
											+ TimeFormatter.formatColonMillis(SystemTime.getCurrentTime() - start) + ")");
								}
								
								decreaseActive( download );
							}
						});
			}
		}catch( Throwable e ){
		}
	}
	
	/**
	 * this_mon held on entry
	 * @param dl
	 * @param scrape_time
	 */
	private void
	setInteresting(
		Download		dl,
		long			scrape_time )
	{
		interesting_downloads.put( dl, scrape_time );
		
		DownloadScrapeResult sr;
		
		synchronized( OUR_SCRAPE_RESULT_KEY ){
			
			sr = (DownloadScrapeResult)dl.getUserData( OUR_SCRAPE_RESULT_KEY );
			
			if ( sr == null ){
				
				sr = createOurScrapeResult(dl, dl.getTorrent(), -1, -1, -1, scrape_time );
			}
		}
		
		dl.setScrapeResult( sr );
	}
	
	protected void
	processNonRegistrations()
	{
		Download	ready_download 				= null;
		boolean		ready_download_scrape_only	= false;
		long		ready_download_next_check	= -1;
		
		long	now = plugin_interface.getUtilities().getCurrentSystemTime();
		
			// unfortunately getting scrape results can acquire locks and there is a vague
			// possibility of deadlock here, so pre-fetch the scrape results
		
		List<Download>	to_scrape = new ArrayList<Download>();
		
		try{
			this_mon.enter();

			Iterator<Download>	it = interesting_downloads.keySet().iterator();
			
			while( it.hasNext() && ready_download == null ){
				
				Download	download = it.next();
				
				Torrent	torrent = download.getTorrent();
				
				if ( torrent == null ){
					
					continue;
				}
				
				int[] run_data = running_downloads.get( download );

				if ( run_data == null || run_data[0] == REG_TYPE_DERIVED ){
					
						// looks like we'll need the scrape below
					
					to_scrape.add( download );
				}
			}
		}finally{
			
			this_mon.exit();
		}
		
		Map<Download,DownloadScrapeResult> scrapes = new HashMap<Download,DownloadScrapeResult>();
		
		for (int i=0;i<to_scrape.size();i++){
			
			Download	download = (Download)to_scrape.get(i);
						
			scrapes.put( download, download.getLastScrapeResult());		
		}
		
		try{
			this_mon.enter();

			Iterator<Download>	it = interesting_downloads.keySet().iterator();
			
			while( it.hasNext() && ready_download == null ){
				
				Download	download = it.next();
				
				Torrent	torrent = download.getTorrent();
				
				if ( torrent == null ){
					
					continue;
				}
				
				int[] run_data = running_downloads.get( download );
				
				if ( run_data == null || run_data[0] == REG_TYPE_DERIVED ){
					
		 			DownloadManager core_dm = PluginCoreUtils.unwrapIfPossible( download );

		 				// might be a LWSDownload...
		 			
		 			if ( core_dm != null ){
		 				
			 			DownloadManagerStats stats = core_dm.getStats();
		    			
		    				// hack atm rather than recording 'has ever been started' state just look at data
		    				// might have been added for seeding etc so don't just use bytes downloaded
			 				// this is to avoid scraping/registering downloads that have yet to be
			 				// started (and might be having their enabled networks adjusted)
		    			
		    			if ( stats.getTotalDataBytesReceived() == 0 && stats.getPercentDoneExcludingDND() == 0 ){
		    				
		    				continue;
		    			}
		 			}
		 			
					boolean	force =  torrent.wasCreatedByUs();
					
					if ( !force ){
												
						if ( interesting_pub_max > 0 && interesting_published > interesting_pub_max ){
							
							ready_download_scrape_only = true;
						}
						
						DownloadScrapeResult	scrape = (DownloadScrapeResult)scrapes.get( download );
						
						if ( scrape == null ){
							
								// catch it next time round
							
							continue;
						}
						
						if ( scrape.getSeedCount() + scrape.getNonSeedCount() > NUM_WANT ){
							
							continue;
						}
					}
					
					long	target = ((Long)interesting_downloads.get( download )).longValue();
					
					long check_period = TorrentUtils.isDecentralised( torrent.getAnnounceURL())?INTERESTING_DHT_CHECK_PERIOD:INTERESTING_CHECK_PERIOD;
					
					if ( target <= now ){
						
						ready_download				= download;
						ready_download_next_check 	= now + check_period;
						
						setInteresting( download, new Long( ready_download_next_check ));
												
					}else if ( target - now > check_period ){
						
							// update but don't action
						
						setInteresting( download, new Long( now + (target%check_period)));
					}
				}
			}
			
		}finally{
			
			this_mon.exit();
		}
		
		if ( ready_download != null ){
			
			Download	f_ready_download 				= ready_download;
			boolean 	f_ready_download_scrape_only	= ready_download_scrape_only;
			
			Torrent torrent = ready_download.getTorrent();
			
			if ( ready_download.getFlag( Download.FLAG_METADATA_DOWNLOAD )){

				try{
					this_mon.enter();

					interesting_downloads.remove( f_ready_download );
					
				}finally{
					
					this_mon.exit();
				}
				
			}else{
			
				//System.out.println( "presence query for " + ready_download.getName());
				
				final long start 		= now;
				final long f_next_check = ready_download_next_check;
				
				try{
					final I2PHelperRouterDHT rdht = router.selectDHT( ready_download );
					
					final I2PHelperDHT dht = rdht.getDHT(true);
	
					dht.get(	torrent.getHash(), 
								"Presence query for '" + ready_download.getName() + "'",
								(byte)0,
								INTERESTING_AVAIL_MAX, 
								ANNOUNCE_TIMEOUT,
								//false, false,
								new I2PHelperDHTAdapter()
								{
									private boolean diversified;
									private int 	leechers = 0;
									private int 	seeds	 = 0;
									
									@Override
									public void
									valueRead(
										DHTTransportContactI2P		contact,
										String						host,
										int							contact_state )
									{
										if ( contact_state == CS_SEED ){
	
											seeds++;
											
										}else{
											
											leechers++;
										}
									}
									
									@Override
									public void
									complete(
										boolean		timeout )
									{
										// System.out.println( "    presence query for " + f_ready_download.getName() + "->" + total + "/div = " + diversified );
		
										int	total = leechers + seeds;
										
										log( torrent,
												"Presence query: availability="+
												(total==INTERESTING_AVAIL_MAX?(INTERESTING_AVAIL_MAX+"+"):(total+"")) + ",div=" + diversified +
												" (elapsed=" + TimeFormatter.formatColonMillis(SystemTime.getCurrentTime() - start) + ")" + rdht.getName());
												
										if ( diversified ){
											
												// reduce DHT load, don't process further
											
											try{
												this_mon.enter();
		
												interesting_downloads.remove( f_ready_download );
												
											}finally{
												
												this_mon.exit();
											}
											
										}else if ( total < INTERESTING_AVAIL_MAX ){
											
												// once we're registered we don't need to process this download any
												// more unless it goes active and then inactive again
											
											/* remove this for the moment, we want to keep scraping it
											try{
												this_mon.enter();
		
												interesting_downloads.remove( f_ready_download );
												
											}finally{
												
												this_mon.exit();
											}
											*/
											
											if ( !f_ready_download_scrape_only ){
												
												interesting_published++;
																							
												dht.put( 
													torrent.getHash(),
													"Presence store '" + f_ready_download.getName() + "'",
													//"0".getBytes(),	// port 0, no connections
													(byte)0,
													new I2PHelperDHTAdapter()
													{
													});
											}
										}
										
										
										try{
											this_mon.enter();
										
											int[] run_data = running_downloads.get( f_ready_download );
											
											if ( run_data == null ){
												
												run_data = run_data_cache.get( f_ready_download );
											}
											
											if ( run_data != null ){
	
												if ( total < INTERESTING_AVAIL_MAX ){
												
													run_data[1] = seeds;
													run_data[2]	= leechers;
													run_data[3] = total;
													
												}else{
													
													run_data[1] = Math.max( run_data[1], seeds );
													run_data[2] = Math.max( run_data[2], leechers );
												}
												
												run_data[4] = (int)(SystemTime.getCurrentTime()/1000);
											}
										}finally{
											
											this_mon.exit();
										}
										
										DownloadScrapeResult sr = createOurScrapeResult( f_ready_download, torrent, seeds, leechers, SystemTime.getCurrentTime(), f_next_check );
										
										f_ready_download.setScrapeResult( sr );
									}
								});
				}catch( Throwable e ){
				}
			}
		}
	}
	
	private DownloadScrapeResult
	createOurScrapeResult(
		Download		dl,
		Torrent			torrent,
		int				seeds,
		int				leechers,
		long			start,
		long			next )
	{
		DownloadScrapeResult sr = 
			new DownloadScrapeResult()
			{
				@Override
				public Download
				getDownload()
				{
					return( null );
				}
				
				@Override
				public int
				getResponseType()
				{
					return( RT_SUCCESS );
				}
				
				@Override
				public int
				getSeedCount()
				{
					return( seeds );
				}
				
				@Override
				public int
				getNonSeedCount()
				{
					return( leechers );
				}

				@Override
				public long
				getScrapeStartTime()
				{
					return( start );
				}
					
				@Override
				public void
				setNextScrapeStartTime(
					long nextScrapeStartTime)
				{
				}
				
				@Override
				public long
				getNextScrapeStartTime()
				{
					try{
						this_mon.enter();
						
						if ( !running_downloads.containsKey( dl )){
							
								// pick up actual next scrape value
							
							Long real_next = interesting_downloads.get( dl );
							
							if ( real_next != null ){
								
								return( real_next );
							}								
						}
						
						return( next );
						
					}finally{
						
						this_mon.exit();
					}
				}
				
				@Override
				public String
				getStatus()
				{
					return( "OK" );
				}

				@Override
				public URL
				getURL()
				{
					URL	url = torrent != null && torrent.isDecentralised()?torrent.getAnnounceURL():DEFAULT_URL;

					return( url );
				}
			};
			
		synchronized( OUR_SCRAPE_RESULT_KEY ){
				
			dl.setUserData( OUR_SCRAPE_RESULT_KEY, sr );
		}
			
		return( sr );
	}
	
	
	@Override
	public void
	stateChanged(
		Download		download,
		int				old_state,
		int				new_state )
	{
		int	state = download.getState();
		
		try{
			this_mon.enter();

			if ( 	state == Download.ST_DOWNLOADING ||
					state == Download.ST_SEEDING ||
					state == Download.ST_QUEUED ){	// included queued here for the mo to avoid lots
													// of thrash for torrents that flip a lot
				
				if ( running_downloads.containsKey( download )){
					
						// force requery
					
					query_map.put( download, new Long( SystemTime.getCurrentTime()));
				}
			}
		}finally{
			
			this_mon.exit();
		}
		
			// don't do anything if paused as we want things to just continue as they are (we would force an announce here otherwise)
		
		if ( !download.isPaused()){
		
			checkDownloadForRegistration( download, false );
		}
	}
 
	public void
	announceAll()
	{
		adapter.log( "Announce-all requested" );
		
		Long now = new Long( SystemTime.getCurrentTime());
		
		try{
			this_mon.enter();

			Iterator<Map.Entry<Download,Long>> it = query_map.entrySet().iterator();
			
			while( it.hasNext()){
				
				Map.Entry<Download,Long>	entry = it.next();
				
				entry.setValue( now );
			}
		}finally{
			
			this_mon.exit();
		}		
	}
	
	private void
	announce(
		Download	download )
	{
		adapter.log( "Announce requested for " + download.getName());
				
		try{
			this_mon.enter();
			
			query_map.put(download,  SystemTime.getCurrentTime());
			
		}finally{
			
			this_mon.exit();
		}	
	}
	
	@Override
	public void
	positionChanged(
		Download		download, 
		int 			oldPosition,
		int 			newPosition )
	{
	}
	
	protected void
	configChanged()
	{
		Download[] downloads = plugin_interface.getDownloadManager().getDownloads();
	
		for (int i=0;i<downloads.length;i++){
			
			checkDownloadForRegistration(downloads[i], false );
		}
	}
	
	protected void
	increaseActive(
		Download		dl )
	{
		try{
			this_mon.enter();
		
			Integer	active_i = (Integer)in_progress.get( dl );
			
			int	active = active_i==null?0:active_i.intValue();
			
			in_progress.put( dl, new Integer( active+1 ));
			
		}finally{
			
			this_mon.exit();
		}
	}
	
	protected void
	decreaseActive(
		Download		dl )
	{
		try{
			this_mon.enter();
		
			Integer	active_i = (Integer)in_progress.get( dl );
			
			if ( active_i == null ){
				
				Debug.out( "active count inconsistent" );
				
			}else{
				
				int	active = active_i.intValue()-1;
			
				if ( active == 0 ){
					
					in_progress.remove( dl );
					
				}else{
					
					in_progress.put( dl, new Integer( active ));
				}
			}
		}finally{
			
			this_mon.exit();
		}
	}
		
	protected boolean
	isActive(
		Download		dl )
	{
		try{
			this_mon.enter();
			
			return( in_progress.get(dl) != null );
			
		}finally{
			
			this_mon.exit();
		}
	}
	
	protected class
	RegistrationDetails
	{
		private static final int DERIVED_ACTIVE_MIN_MILLIS	= 2*60*60*1000;
		
		private putDetails			put_details;
		private byte				flags;
		private trackerTarget[]		put_targets;
		private List<trackerTarget>	not_put_targets;
		
		private long			derived_active_start	= -1;
		private long			previous_metric;
		
		protected
		RegistrationDetails(
			Download			_download,
			int					_reg_type,
			putDetails			_put_details,
			byte				_flags )
		{
			put_details		= _put_details;
			flags			= _flags;
			
			getTrackerTargets( _download, _reg_type );
		}
		
		protected void
		update(
			putDetails		_put_details,
			byte			_flags )
		{
			put_details	= _put_details;
			flags		= _flags;
		}
		
		protected boolean
		updateTargets(
			Download			_download,
			int					_reg_type )
		{
			trackerTarget[]	old_put_targets = put_targets;
			 
			getTrackerTargets( _download, _reg_type );
			
				// first remove any redundant entries
			
			for (int i=0;i<old_put_targets.length;i++){
				
				boolean	found = false;
				
				byte[]	old_hash = old_put_targets[i].getHash();
				
				for (int j=0;j<put_targets.length;j++){
					
					if ( Arrays.equals( put_targets[j].getHash(), old_hash )){
						
						found	= true;
						
						break;
					}
				}
				
				if ( !found ){
					
					trackerRemove( _download, old_put_targets[i] );
				}
			}
			
				// now look to see if we have any new stuff 
			
			boolean	changed = false;
			
			for (int i=0;i<put_targets.length;i++){
								
				byte[]	new_hash = put_targets[i].getHash();
				
				boolean	found = false;
				
				for (int j=0;j<old_put_targets.length;j++){
					
					if ( Arrays.equals( old_put_targets[j].getHash(), new_hash )){
						
						found = true;
						
						break;
					}
				}
				
				if ( !found ){
					
					changed = true;
				}
			}
			
			return( changed );
		}
		
		protected putDetails
		getPutDetails()
		{
			return( put_details );
		}
		
		protected byte
		getFlags()
		{
			return( flags );
		}
		
		protected trackerTarget[]
		getTargets(
			boolean		for_put )
		{
			if ( for_put || not_put_targets == null ){
				
				return( put_targets );
				
			}else{
			
				List<trackerTarget>	result = new ArrayList<trackerTarget>( Arrays.asList( put_targets ));
				
				for (int i=0;i<not_put_targets.size()&& i < 2; i++ ){
					
					trackerTarget target = (trackerTarget)not_put_targets.remove(0);
					
					not_put_targets.add( target );
					
					// System.out.println( "Mixing in " + target.getDesc());
					
					result.add( target );
				}
				
				return( (trackerTarget[])result.toArray( new trackerTarget[result.size()]));
			}
		}
		
		protected void
    	getTrackerTargets(
    		Download		download,
    		int				type )
    	{
    		byte[]	torrent_hash = download.getTorrent().getHash();
    		
    		List<trackerTarget>	result = new ArrayList<trackerTarget>();
    		
    		if ( type == REG_TYPE_FULL ){
    			
    			result.add( new trackerTarget( torrent_hash, REG_TYPE_FULL, "" ));
    		}
    		
	    	put_targets 	= result.toArray( new trackerTarget[result.size()]);
    	}
	}
	
	
	private void
	log(
		Download		download,
		String			str )
	{
		log( download.getTorrent(), str );
	}
	
	private void
	log(
		Torrent			torrent,
		String			str )
	{
		adapter.log( torrent.getName() + ": " + str );
	}
	

	protected static class
	putDetails
	{
		private String	encoded;
		private String	ip_override;
		private int		tcp_port;
		private int		udp_port;
		
		private
		putDetails(
			String	_encoded,
			String	_ip,
			int		_tcp_port,
			int		_udp_port )
		{
			encoded			= _encoded;
			ip_override		= _ip;
			tcp_port		= _tcp_port;
			udp_port		= _udp_port;
		}
		
		protected String
		getEncoded()
		{
			return( encoded );
		}
		
		protected String
		getIPOverride()
		{
			return( ip_override );
		}
		
		protected int
		getTCPPort()
		{
			return( tcp_port );
		}
		
		protected int
		getUDPPort()
		{
			return( udp_port );
		}
		
		protected boolean
		sameAs(
			putDetails		other )
		{
			return( getEncoded().equals( other.getEncoded()));
		}
	}
	
	public static class
	trackerTarget
	{
		private String		desc;
		private	byte[]		hash;
		private int			type;
		
		protected
		trackerTarget(
			byte[]			_hash,
			int				_type,
			String			_desc )
		{
			hash		= _hash;
			type		= _type;
			desc		= _desc;
		}
		
		public int
		getType()
		{
			return( type );
		}
		
		public byte[]
		getHash()
		{
			return( hash );
		}
		
		public String
		getDesc(
			I2PHelperRouterDHT	rdht )
		{
			return( getDesc() + rdht.getName());
		}
		
		public String
		getDesc()
		{
			if ( type != REG_TYPE_FULL ){
			
				return( "(" + desc + ")" );
			}
			
			return( "" );
		}
	}
		
	private class
	GetAdapter
		implements I2PHelperDHTListener, DHTPluginOperationListener, TorProxyDHT.TorProxyDHTListener
	{
		final 	long	start = SystemTime.getCurrentTime();

		final I2PHelperRouterDHT	rdht;
		final Download				download;
		final RegistrationDetails	details;
		final trackerTarget			target;
		final boolean				derived_only;
		final long[]				max_retry;
		
		final Torrent				torrent;
		final URL					url_to_report;
		
		final List<Object[]>	peers 	= new ArrayList<Object[]>( NUM_WANT );
		
		final Map<String,Object[]>	i2p_tor_peers = new HashMap<>();
		final Map<String,Object[]>	proxy_tor_peers = new HashMap<>();
		
		int		seed_count;
		int		leecher_count;
		
		int		complete_count;
		
		boolean	complete;
		
		private
		GetAdapter(
			I2PHelperRouterDHT	_rdht,
			Download			_download,
			RegistrationDetails	_details,
			trackerTarget		_target,
			boolean				_derived_only,
			long[]				_max_retry,
			int					_complete_count )
		{
			rdht			= _rdht;
			download		= _download;
			details			= _details;
			target			= _target;
			derived_only	= _derived_only;
			max_retry		= _max_retry;
			
			torrent = download.getTorrent();
			
			url_to_report = torrent.isDecentralised()?torrent.getAnnounceURL():DEFAULT_URL;
			
			complete_count = _complete_count;
		}
		
			// I2PHelperDHTListener methods
		
		@Override
		public void 
		searching(String host)
		{
		}
		
		@Override
		public void
		valueRead(
			DHTTransportContactI2P		contact,
			String						host,
			int							contact_state )
		{
			synchronized( this ){
				
				if ( complete ){
					
					return;
				}

				try{														
					peers.add( new Object[]{ host, 6881, contact_state==CS_SEED, false }); 
					
					if ( contact_state ==  CS_SEED ){
													
						seed_count++;
					}else{
						
						leecher_count++;
					}			
				}catch( Throwable e ){
					
					// in case we get crap back (someone spamming the DHT) just
					// silently ignore
				}
			}
		}

		@Override
		public void
		complete(
			boolean		timeout )
		{
			completeSupport();
		}
		
		private void
		completeSupport()
		{
			int	init_peers		= peers.size();
			int init_i2p_tor	= i2p_tor_peers.size();
			int init_proxy_tor	= proxy_tor_peers.size();
			
			synchronized( this ){
				
				complete_count--;
				
				if ( complete_count > 0 ){
					
					return;
				}
				
				if ( complete ){
					
					Debug.out( "Already complete!!!!" );
					
					return;
				}
				
				complete = true;
								
				if ( !i2p_tor_peers.isEmpty()){
				
					if ( !proxy_tor_peers.isEmpty()){
						
						for ( Object[] peer: i2p_tor_peers.values()){
							
							proxy_tor_peers.remove( peer[0] );
						}
					}
					
					boolean prefer_tor = router.getPlugin().preferTorPeers();
					
					for ( int i=0; i<peers.size(); i++ ){
						
						Object[] peer = (Object[])peers.get(i);
					
						String host = (String)peer[0];
						
						Object[] tor_peer = i2p_tor_peers.remove( host );
						
						if ( tor_peer != null ){
							
							if ( prefer_tor ){
													
								peers.set( i, tor_peer );
							}
						}
					}
					
					if ( !i2p_tor_peers.isEmpty()){
												
						peers.addAll( i2p_tor_peers.values());
					}
				}
				
				if ( !proxy_tor_peers.isEmpty()){
					
					peers.addAll( proxy_tor_peers.values());
				}
			}
			
			if ( 	target.getType() == REG_TYPE_FULL ||
					(	target.getType() == REG_TYPE_DERIVED && 
						seed_count + leecher_count > 1 )){
				
				log( 	download,
						"Get of '" + target.getDesc(rdht) + "' completed (elapsed=" + TimeFormatter.formatColonMillis(SystemTime.getCurrentTime() - start)
								+ "), peers=" + peers.size() + " (" + init_peers + "/" + init_i2p_tor + "/" + init_proxy_tor + "), seeds="
								+ seed_count + ", leechers=" + leecher_count);
			}
		
			decreaseActive(download);
			
			int	peers_found = peers.size();
			
			List<DownloadAnnounceResultPeer>	peers_for_announce = new ArrayList<DownloadAnnounceResultPeer>();
			
			final long	retry;
			
			if ( download.getFlag( Download.FLAG_METADATA_DOWNLOAD ) && peers_found < 5){
				
				retry = ANNOUNCE_METADATA_RETRY;
				
			}else{
				
				
					// scale min and max based on number of active torrents
					// we don't want more than a few announces a minute
				
				int	announce_per_min = 4;
				
				int	num_active = query_map.size();
				
				int	announce_min = Math.max( ANNOUNCE_MIN_DEFAULT, ( num_active / announce_per_min )*60*1000 );
				
				int	announce_max = derived_only?ANNOUNCE_MAX_DERIVED_ONLY:ANNOUNCE_MAX;
				
				announce_min = Math.min( announce_min, announce_max );
															
				retry = announce_min + peers_found*(long)(announce_max-announce_min)/NUM_WANT;
			}
			
			int download_state = download.getState();
			
			boolean	we_are_seeding = download_state == Download.ST_SEEDING;

			try{
				this_mon.enter();
			
				int[] run_data = running_downloads.get( download );
				
				if ( run_data != null ){

					boolean full = target.getType() == REG_TYPE_FULL;
					
					int peer_count = we_are_seeding?leecher_count:(seed_count+leecher_count);
					
					run_data[1] = full?seed_count:Math.max( run_data[1], seed_count);
					run_data[2]	= full?leecher_count:Math.max( run_data[2], leecher_count);
					run_data[3] = full?peer_count:Math.max( run_data[3], peer_count);
						
					run_data[4] = (int)(SystemTime.getCurrentTime()/1000);
					
					long	absolute_retry = SystemTime.getCurrentTime() + retry;
				
					if ( absolute_retry > max_retry[0] ){
							
							// only update next query time if none set yet 
							// or we appear to have set the existing one. If we
							// don't do this then we'll overwrite any rescheduled
							// announces
						
						Long	existing = (Long)query_map.get( download );
						
						if ( 	existing == null ||
								existing.longValue() == max_retry[0] ){
							
							max_retry[0] = absolute_retry;
					
							query_map.put( download, new Long( absolute_retry ));
						}
					}
				}						
			}finally{
				
				this_mon.exit();
			}
										
			putDetails put_details = details.getPutDetails();
			
			String	ext_address = put_details.getIPOverride();
			
			if ( ext_address == null ){
					
				try{
					ext_address = router.selectDHT( download ).getDHT(true).getLocalAddress();
					
				}catch( Throwable e ){	
					
					ext_address = "";
				}
			}
			
			for (int i=0;i<peers.size();i++){
				
				Object[] peer = peers.get(i);
				
				String	ip				= (String)peer[0];
				int		port			= (Integer)peer[1];
				boolean	is_seed			= (Boolean)peer[2];
				boolean	crypto_required	= (Boolean)peer[3];
				
					// when we are seeding ignore seeds
				
				if ( we_are_seeding && is_seed ){
					
					continue;
				}
				
					// remove ourselves
								
				if ( ip.equals( ext_address )){
														
					continue;
				}
								
				peers_for_announce.add(
					new DownloadAnnounceResultPeer()
					{
						@Override
						public String
						getSource()
						{
							return( PEPeerSource.PS_DHT );
						}
						
						@Override
						public String
						getAddress()
						{
							return( ip );
						}
						
						@Override
						public int
						getPort()
						{
							return( port );
						}
						
						@Override
						public int
						getUDPPort()
						{
							return( 0 );
						}
						
						@Override
						public byte[]
						getPeerID()
						{
							return( null );
						}
						
						@Override
						public short
						getProtocol()
						{
							return( crypto_required?PROTOCOL_CRYPT:PROTOCOL_NORMAL );
						}
					});
				
			}
				
			if ( target.getType() == REG_TYPE_DERIVED && peers_for_announce.size() > 0 ){
				
				PeerManager pm = download.getPeerManager();
				
				if ( pm != null ){
						
						// try some limited direct injection
					
					List<DownloadAnnounceResultPeer>	temp = new ArrayList<DownloadAnnounceResultPeer>( peers_for_announce );
					
					Random rand = new Random();
					
					for (int i=0;i<DIRECT_INJECT_PEER_MAX && temp.size() > 0; i++ ){
						
						DownloadAnnounceResultPeer peer = temp.remove( rand.nextInt( temp.size()));
						
						log( download, "Injecting derived peer " + peer.getAddress() + " into " + download.getName());
						
						Map<Object,Object>	user_data = new HashMap<Object,Object>();
																
						user_data.put( Peer.PR_PRIORITY_CONNECTION, new Boolean( true ));

						pm.addPeer( 
								peer.getAddress(),
								peer.getPort(),
								peer.getUDPPort(),
								peer.getProtocol() == DownloadAnnounceResultPeer.PROTOCOL_CRYPT,
								user_data );
					}
				}
			}
			
			if ( 	download_state == Download.ST_DOWNLOADING ||
					download_state == Download.ST_SEEDING ){
			
				final DownloadAnnounceResultPeer[]	peers = new DownloadAnnounceResultPeer[peers_for_announce.size()];
				
				peers_for_announce.toArray( peers );
				
				download.setAnnounceResult(
						new DownloadAnnounceResult()
						{
							@Override
							public Download
							getDownload()
							{
								return( download );
							}
																		
							@Override
							public int
							getResponseType()
							{
								return( DownloadAnnounceResult.RT_SUCCESS );
							}
																	
							@Override
							public int
							getReportedPeerCount()
							{
								return( peers.length);
							}
									
							@Override
							public int
							getSeedCount()
							{
								return( seed_count );
							}
							
							@Override
							public int
							getNonSeedCount()
							{
								return( leecher_count );	
							}
							
							@Override
							public String
							getError()
							{
								return( null );
							}
																		
							@Override
							public URL
							getURL()
							{
								return( url_to_report );
							}
							
							@Override
							public DownloadAnnounceResultPeer[]
							getPeers()
							{
								return( peers );
							}
							
							@Override
							public long
							getTimeToWait()
							{
								return( retry/1000 );
							}
							
							@Override
							public Map
							getExtensions()
							{
								return( null );
							}
						});
			}
				
				// only inject the scrape result if the torrent is decentralised. If we do this for
				// "normal" torrents then it can have unwanted side-effects, such as stopping the torrent
				// due to ignore rules if there are no downloaders in the DHT - bthub backup, for example,
				// isn't scrapable...
			
				// hmm, ok, try being a bit more relaxed about this, inject the scrape if
				// we have any peers. 
												
			boolean	inject_scrape = leecher_count > 0;
			
			DownloadScrapeResult result = download.getLastScrapeResult();
												
			if (	result == null || 
					result.getResponseType() == DownloadScrapeResult.RT_ERROR ){									

			}else{
			
					// if the currently reported values are the same as the 
					// ones we previously injected then overwrite them
					// note that we can't test the URL to see if we originated
					// the scrape values as this gets replaced when a normal
					// scrape fails :(
					
				int[]	prev = (int[])scrape_injection_map.get( download );
					
				if ( 	prev != null && 
						prev[0] == result.getSeedCount() &&
						prev[1] == result.getNonSeedCount()){
																		
					inject_scrape	= true;
				}
			}
			
			if ( torrent.isDecentralised() || inject_scrape ){
				
				
					// make sure that the injected scrape values are consistent
					// with our currently connected peers
				
				PeerManager	pm = download.getPeerManager();
				
				int	local_seeds 	= 0;
				int	local_leechers 	= 0;
				
				if ( pm != null ){
					
					Peer[]	dl_peers = pm.getPeers();
					
					for (int i=0;i<dl_peers.length;i++){
						
						Peer	dl_peer = dl_peers[i];
						
						if ( dl_peer.getPercentDoneInThousandNotation() == 1000 ){
							
							local_seeds++;
							
						}else{
							local_leechers++;
						}
					}							
				}
				
				final int f_adj_seeds 		= Math.max( seed_count, local_seeds );
				final int f_adj_leechers	= Math.max( leecher_count, local_leechers );
				
				scrape_injection_map.put( download, new int[]{ f_adj_seeds, f_adj_leechers });

				try{
					this_mon.enter();
				
					int[] run_data = running_downloads.get( download );
					
					if ( run_data == null ){
						
						run_data = run_data_cache.get( download );
					}
					
					if ( run_data != null ){

						run_data[1] = f_adj_seeds;
						run_data[2]	= f_adj_leechers;
						
						run_data[4] = (int)(SystemTime.getCurrentTime()/1000);
					}
				}finally{
					
					this_mon.exit();
				}
				
				DownloadScrapeResult sr = createOurScrapeResult( download, torrent, f_adj_seeds, f_adj_leechers, start, SystemTime.getCurrentTime() + retry );
				
				download.setScrapeResult( sr );
			}	
		}
		
			// DHTPluginOperationListener methods
		
		@Override
		public void
		starts(
			byte[]				key )
		{
		}

		@Override
		public boolean
		diversified()
		{
			return( true );
		}
		
		@Override
		public void 
		valueRead(
			DHTPluginContact	originator, 
			DHTPluginValue		plugin_value )
		{
			byte[] value = plugin_value.getValue();
			
			byte[] hash = target.getHash();
			
			TorEndpoint our_tep = router.getPlugin().getTorEndpoint( rdht.getDHTIndex());
			
			String our_tor_host = our_tep.getHost();
			
			try{
				Map map = BDecoder.decode( value );
				
				byte[] thb = (byte[])map.get( "th" );
				
				if ( thb != null ){
					
					for ( int j=0;j<thb.length;j++){
						
						thb[j] ^= hash[j%hash.length];
					}
				
					boolean crypto_required	= false;
					boolean is_seed			= false;
					
					Number n_flags = (Number)map.get( "f" );
					
					if ( n_flags != null ){
						
						int flags = n_flags.intValue();
						
						crypto_required = (flags&0x01) != 0;
						is_seed			= (flags&0x02) != 0;
					}
					
					String th = new String( thb, Constants.UTF_8 ) + ".onion";
				
					if ( !th.equals( our_tor_host )){
											
						int port = our_tep.getPort();
						
						InetSocketAddress o_address = originator.getAddress();
						
						//System.out.println( o_address.getHostName() + ":" + o_address.getPort() + " -> " + th + ", seed=" + is_seed + ", crypto=" + crypto_required + ", port=" + port );
						
						synchronized( this ){
							
							if ( complete ){
								
								return;
							}
							
							i2p_tor_peers.put( o_address.getHostName(), new Object[]{ th, port, is_seed, crypto_required });
						}
					}
				}
				
			}catch( Throwable e ){									
			}
		}
		
		@Override
		public void
		valueWritten(
			DHTPluginContact	target,
			DHTPluginValue		value )
		{
		}

		@Override
		public void
		complete(
			byte[]				key,
			boolean				timeout_occurred )
		{
			completeSupport();
		}
		
			// TorProxyDHT.TorProxyDHTListener methods
		
		public void
		proxyValueRead(
			InetSocketAddress	originator,
			boolean				is_seed,
			boolean				crypto_required )
		{
			// System.out.println( "Announce reply: " + originator + "/" + is_seed + "/" + crypto_required );
			
			TorEndpoint our_tep = router.getPlugin().getTorEndpoint( rdht.getDHTIndex());
			
			String our_tor_host = our_tep.getHost();
			
			try{
				String host = AddressUtils.getHostAddress(originator);
				
				if ( !host.equals( our_tor_host )){
																
					synchronized( this ){
						
						if ( complete ){
							
							return;
						}
						
						proxy_tor_peers.put( host, new Object[]{ host, originator.getPort(), is_seed, crypto_required });
					}
				}
			}catch( Throwable e ){									
			}
		}
		
		public void
		proxyComplete(
			byte[]		key,
			boolean		timeout )
		{
			completeSupport();
		}				
	};
}
