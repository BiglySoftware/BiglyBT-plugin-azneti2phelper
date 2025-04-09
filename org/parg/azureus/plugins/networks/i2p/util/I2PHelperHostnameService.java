/*
 * Created on Feb 13, 2015
 * Created by Paul Gardner
 * 
 * Copyright 2015 Azureus Software, Inc.  All rights reserved.
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


package org.parg.azureus.plugins.networks.i2p.util;

import java.text.SimpleDateFormat;
import java.util.*;
import java.io.*;

import org.gudy.bouncycastle.util.encoders.Base64;
import org.parg.azureus.plugins.networks.i2p.I2PHelperPlugin;

import com.biglybt.core.util.AENetworkClassifier;
import com.biglybt.core.util.BDecoder;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.plugin.net.buddy.BuddyPluginUtils;
import com.biglybt.util.MapUtils;
import com.biglybt.plugin.net.buddy.BuddyPluginBeta.ChatAdapter;
import com.biglybt.plugin.net.buddy.BuddyPluginBeta.ChatInstance;
import com.biglybt.plugin.net.buddy.BuddyPluginBeta.ChatMessage;

import net.i2p.data.Base32;
import net.i2p.data.Destination;

public class 
I2PHelperHostnameService 
{
		// Note that http://inr.i2p/export/alive-hosts.txt is a good source for updating hosts.txt as well
	
		// also parse jump response: http://inr.i2p/<hostname>.i2p -> address
	
	private I2PHelperPlugin		plugin;
	private File				i2hostetag_file;
	private File				dnsfeed_base_file;
	private File				dnsfeed_file;
	
	private Map<String,String>	result_cache = new HashMap<String, String>();
	
	private Map<String,String>	dnsfeed_cache = null;
	
	public
	I2PHelperHostnameService(
		I2PHelperPlugin		_plugin,
		File				plugin_dir )
	{
		plugin				= _plugin;
		i2hostetag_file 	= new File( plugin_dir, "i2hostetag.b32.txt" );
		dnsfeed_base_file	= new File( plugin_dir, "dnsfeed_base.txt" );
		dnsfeed_file	 	= new File( plugin_dir, "dnsfeed.txt" );
		
		SimpleTimer.addEvent(
			"init",
			SystemTime.getOffsetTime( 60*1000 ),
			new TimerEventPerformer(){
				
				@Override
				public void perform(TimerEvent event){
					try{
						Map<String,Object>	options = new HashMap<String, Object>();
						
						options.put( ChatInstance.OPT_INVISIBLE, true );
														
						ChatInstance chat = 
								BuddyPluginUtils.getChat(
									AENetworkClassifier.AT_PUBLIC, 
									"BiglyBT: I2P: DNS Feed[pk=AS3W2WHFGFQMD2KU7Z2YD2IMYIZJ5RG34AW4URPKPQ7NJRCZKJU2PDR4IGGECFLJMOQMARA72SDJ42Y&ro=1]",
									options );

						if ( chat != null ){
							
							chat.setSharedNickname( false );
							
							chat.setSaveMessages( true );
							
							chat.addListener(
								new ChatAdapter()
								{
									@Override
									public void 
									messageReceived(
										ChatMessage 	msg,
										boolean			sort_outstanding )
									{
										receiveDNSFeedMessage( msg );
									}
								});
							
							List<ChatMessage>	messages = chat.getMessages();
							
							for ( ChatMessage msg: messages ){
								
								receiveDNSFeedMessage( msg );
							}
						}
					}catch( Throwable e ){
						
						Debug.out( e );
					}
				}
			});
		
		SimpleTimer.addPeriodicEvent(
			"tidy",
			2*60*1000,
			new TimerEventPerformer(){
				
				@Override
				public void perform(TimerEvent event){
				
					synchronized( I2PHelperHostnameService.this ){
						
						dnsfeed_cache = null;
					}
				}
			});
	}

	public String
	lookup(
		String 	_hostname )
	{
		String hostname = _hostname.toLowerCase( Locale.US );
		
		if ( AENetworkClassifier.categoriseAddress( hostname ) != AENetworkClassifier.AT_I2P ){
			
			return( null );
		}

		String result = lookupDNSFeed( hostname );
		
		if ( result != null ){
			
			return( result );
		}
		
		return( lookupI2hostetag( hostname ));
	}
	
	private Map<String,String>
	loadDNSFeedCache()
	{
		synchronized( this ){
						
			if ( dnsfeed_cache == null ){
				
				dnsfeed_cache = new HashMap<>();
				
				if ( dnsfeed_base_file.exists()){
					
					try{
						LineNumberReader	lnr = new LineNumberReader( new FileReader( dnsfeed_base_file ));
						
						try{
							while( true ){
								
								String line = lnr.readLine();
								
								if ( line == null ){
									
									break;
								}
																
								line = line.trim();
								
								if ( line.startsWith( "#" )){
									
									continue;
								}
								
								String[] bits = line.split( "=", 2 );
								
								if ( bits.length == 2 ){
									
									String host = bits[0];
									String dest	= bits[1];
									
									dnsfeed_cache.put( host,  dest );
								}
							}
						}catch( Throwable e ){
							
							Debug.out( e );
							
						}finally{
							
							lnr.close();
						}
					}catch( Throwable e ){
						
						Debug.out( e );
					}
				}
				
				if ( dnsfeed_file.exists()){				

					Set<String>		dup_set		= new HashSet<>();
					Set<Integer>	dup_lines 	= new HashSet<>();
					
					try{
						int	line_number = 0;
						
						LineNumberReader	lnr = new LineNumberReader( new FileReader( dnsfeed_file ));
						
						try{
							while( true ){
								
								String line = lnr.readLine();
								
								if ( line == null ){
									
									break;
								}
								
								line_number++;
								
								line = line.trim();
								
								if ( line.startsWith( "#" )){
									
									continue;
								}
								
								String[] bits = line.split( "=", 2 );
								
								if ( bits.length == 2 ){
									
									String host = bits[0];
									String dest	= bits[1];
									
									dnsfeed_cache.put( host,  dest );
									
									if ( dup_set.contains( line )){
											
										dup_lines.add( line_number );
										
									}else{
										
										dup_set.add( line );
									}
								}
							}
						}catch( Throwable e ){
							
							Debug.out( e );
							
						}finally{
							
							lnr.close();
						}
						
						if ( !dup_lines.isEmpty()){
							
							line_number = 0;
						
							File tmp_file = new File( dnsfeed_file.getParentFile(), dnsfeed_file.getName() + ".tmp" );
							
							tmp_file.delete();
															
							lnr = new LineNumberReader( new FileReader( dnsfeed_file ));
								
							PrintWriter pw = new PrintWriter( new OutputStreamWriter( new FileOutputStream( tmp_file, false ), "UTF-8" ));
								
							try{
								while( true ){
									
									String line = lnr.readLine();
									
									if ( line == null ){
										
										break;
									}
									
									line_number++;
									
									line = line.trim();
									
									if ( !dup_lines.contains( line_number )){
										
										pw.println( line );
									}
								}
								
								
								pw.close();
								
								pw = null;
								
								lnr.close();
								
								lnr = null;

								dnsfeed_file.delete();
								
								tmp_file.renameTo( dnsfeed_file );
								
							}catch( Throwable e ){
								
								Debug.out( e );
								
							}finally{
								
								if ( lnr != null ){
									try{
										lnr.close();
									}catch( Throwable e ){
										Debug.out( e );
									}
								}
								
								if ( pw != null ){
									try{
										pw.close();
									}catch( Throwable e ){
										Debug.out( e );
									}
								}
								
								if ( tmp_file.exists()){
									
									tmp_file.delete();
								}
							}
						}
					}catch( Throwable e ){
						
						Debug.out( e );
					}
				}
			}
			
			return( dnsfeed_cache );
		}
	}
	
	private void
	receiveDNSFeedMessage(
		ChatMessage		msg )
	{
		if ( msg.getMessageType() != ChatMessage.MT_NORMAL ){
			
			return;
		}
		
		try{
			Map map = BDecoder.decode( msg.getRawMessage());
					
			String host = MapUtils.getMapString(map, "h", null );
			
			byte[] dest_bytes = (byte[])map.get( "a" );
			
			String dest_str = new String( Base64.encode( dest_bytes ), "UTF-8" );
			
			dest_str = dest_str.replace('/', '~');
			dest_str = dest_str.replace('+', '-');
			
			String host_prefix = null;
			
			if (  host.endsWith( ".i2p" )){
				
				host_prefix = host.substring( 0, host.length() - 4 );
				
			}else if ( host.endsWith( ".i2p.alt" )){
				
				host_prefix = host.substring( 0, host.length() - 8 );
			}
			
			if ( host_prefix != null ){
				
				synchronized( this ){
					
					Map<String,String> cache = loadDNSFeedCache();
					
					String existing = cache.get( host_prefix );
					
					if ( existing != null && existing.equals( dest_str )){
						
						return;
					}
						
					PrintWriter pw = new PrintWriter( new OutputStreamWriter( new FileOutputStream( dnsfeed_file.getAbsolutePath(), true ), "UTF-8" ));
						
					pw.println( host_prefix + "=" + dest_str );
						
					pw.close();
						
					cache.put( host_prefix, dest_str );
					
					result_cache.put( host_prefix, dest_str );
				}
			}
			
		}catch( Throwable e ){
			
			Debug.out( e );
		}
	}
	
	private String
	lookupDNSFeed(
		String	hostname )
	{
		synchronized( this ){
			
			if ( !dnsfeed_file.exists()){
				
				return( null );
			}
				
			hostname = hostname.substring( 0, hostname.length() - 4 );
		
			String result = result_cache.get( hostname );
			
			if ( result != null ){
				
				return( result );
			}
			
			Map<String,String> cache = loadDNSFeedCache();
				
			result = cache.get( hostname );
				
			if ( result != null ){
							
				result_cache.put( hostname, result );
				
				plugin.log( "Resolved " + hostname + " to " + result.substring( 0, 32 ) + "..." );
			}
			
			return( result );
		}
	}
	
	private String
	lookupI2hostetag(
		String	hostname )
	{
		if ( !i2hostetag_file.exists()){
			
			return( null );
		}	
		
		hostname = hostname.substring( 0, hostname.length() - 4 );
		
		synchronized( this ){
			
			String existing = result_cache.get( hostname );
			
			if ( existing != null ){
				
				return( existing );
			}
			
			try{
				LineNumberReader	lnr = new LineNumberReader( new FileReader( i2hostetag_file ));
				
				try{
					while( true ){
						
						String line = lnr.readLine();
						
						if ( line == null ){
							
							break;
						}
						
						line = line.trim();
						
						if ( line.startsWith( "#" )){
							
							continue;
						}
						
						String[] bits = line.split( "=", 2 );
						
						if ( bits.length == 2 ){
							
							String 	lhs = bits[0];
							
							if ( lhs.equals( hostname )){
								
								String result = bits[1] + ".b32.i2p";
								
								result_cache.put( hostname, result );
								
								plugin.log( "Resolved " + hostname + " to " + result );
								
								return( result );
							}
						}
					}
				}finally{
					
					lnr.close();
				}
			}catch( Throwable e ){
				
			}
		}
		
		return( null );
	}
	
	public static void
	main(
		String[]		args )
	{
			// downloaded from http://i2host.i2p.xyz/cgi-bin/i2hostetag  or http://i2host.i2p/cgi-bin/i2hostetag
		
		try{
			LineNumberReader	lnr = new LineNumberReader( new FileReader( "C:\\temp\\i2hostetag.txt"));
						
			int	num = 0;
			
			Map<String,String>	host_map = new TreeMap<String,String>();
			
			while( true ){
				
				String line = lnr.readLine();
				
				if ( line == null ){
					
					break;
				}
				
				line = line.trim();
				
				if ( line.startsWith( "#" )){
					
					continue;
				}
				
				String[] bits = line.split( "=", 2 );
				
				if ( bits.length == 2 ){
					
					String 	host = bits[0].trim();
					
					String host_prefix = null;
					
					if (  host.endsWith( ".i2p" )){
						
						host_prefix = host.substring( 0, host.length() - 4 );
						
					}else if ( host.endsWith( ".i2p.alt" )){
						
						host_prefix = host.substring( 0, host.length() - 8 );
					}
					
					if ( host_prefix != null ){
												
						try{
							Destination dest = new Destination();
							
							dest.fromBase64( bits[1].trim());
							
							String b32 = Base32.encode( dest.calculateHash().getData());
							
							if ( host_map.put( host_prefix, b32 ) != null ){
								
								System.out.println( "host updated: " + host_prefix );
								
							}else{
								
								num++;
							}
							
							
						}catch( Throwable e ){
							
							System.out.println( "Bad base64 for " + host_prefix );
							
						}
					}else{
						
						System.out.println( "invalid line: " + line );
					}
				}
			}
			
			System.out.println( "Read " + num + " hosts" );
			
			PrintWriter output = new PrintWriter( new FileWriter( "C:\\temp\\i2hostetag.b32.txt"));

			SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
			
			format.setTimeZone( TimeZone.getTimeZone( "UTC" ));
			
			output.println( "# Automatically generated from http://i2host.i2p/cgi-bin/i2hostetag on " + format.format( new Date( System.currentTimeMillis())));
			
			for ( Map.Entry<String,String> entry: host_map.entrySet() ){
				
				output.println( entry.getKey() + "=" + entry.getValue());
			}
			
			output.close();
			
		}catch( Throwable e ){
			
			e.printStackTrace();
		}
	}
}
