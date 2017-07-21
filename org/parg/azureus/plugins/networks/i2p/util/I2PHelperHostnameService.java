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

import org.parg.azureus.plugins.networks.i2p.I2PHelperPlugin;

import net.i2p.data.Base32;
import net.i2p.data.Destination;

public class 
I2PHelperHostnameService 
{
	private I2PHelperPlugin		plugin;
	private File				hosts;
	
	private Map<String,String>	cache = new HashMap<String, String>();
	
	public
	I2PHelperHostnameService(
		I2PHelperPlugin		_plugin,
		File				plugin_dir )
	{
		plugin	= _plugin;
		hosts 	= new File( plugin_dir, "i2hostetag.b32.txt" );
	}

	public String
	lookup(
		String 	_hostname )
	{
		if ( !hosts.exists()){
			
			return( null );
		}
		
		String hostname = _hostname.toLowerCase( Locale.US );
			
		if ( !hostname.endsWith( ".i2p" )){
			
			return( null );
		}
		
		hostname = hostname.substring( 0, hostname.length() - 4 );
		
		synchronized( this ){
			
			String existing = cache.get( hostname );
			
			if ( existing != null ){
				
				return( existing );
			}
			
			try{
				LineNumberReader	lnr = new LineNumberReader( new FileReader( hosts ));
				
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
								
								cache.put( hostname, result );
								
								plugin.log( "Resolved " + _hostname + " to " + result );
								
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
					
					if ( host.endsWith( ".i2p" )){
						
						host = host.substring( 0, host.length()-4 );
						
						try{
							Destination dest = new Destination();
							
							dest.fromBase64( bits[1].trim());
							
							String b32 = Base32.encode( dest.calculateHash().getData());
							
							if ( host_map.put( host, b32 ) != null ){
								
								System.out.println( "host updated: " + host );
								
							}else{
								
								num++;
							}
							
							
						}catch( Throwable e ){
							
							System.out.println( "Bad base64 for " + host );
							
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
