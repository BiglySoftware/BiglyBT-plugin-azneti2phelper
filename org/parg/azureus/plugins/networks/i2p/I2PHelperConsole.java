/*
 * Created on 09-Nov-2005
 * Created by Paul Gardner
 * Copyright (C) 2005 Aelitis, All Rights Reserved.
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
 * AELITIS, SAS au capital de 40,000 euros
 * 8 Allee Lenotre, La Grille Royale, 78600 Le Mesnil le Roi, France.
 *
 */

package org.parg.azureus.plugins.networks.i2p;


import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;

import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.Debug;

public class 
I2PHelperConsole 
{
	private Object			console;
	private BufferedReader 	in;
	
	private String			request_prompt;
	private boolean			request_is_pw;
	
	private AESemaphore		request_sem = new AESemaphore( "req" );
	
	private Object			reply;
	
	private AESemaphore		reply_sem = new AESemaphore( "rep" );

	private boolean			destroyed;
	
	public
	I2PHelperConsole()
	{
		try{
			console = System.console();
			
		}catch( Throwable e ){
			
		}
		
		if ( console == null ){
			
			System.out.println( "Console unavailable - using System.in" );
			
			in = new BufferedReader( new InputStreamReader( System.in ) );
		}
		
		new AEThread2( "StreamPlugConsole:reader", true )
		{
			@Override
			public void
			run()
			{
				while( true ){
					
					request_sem.reserve();
					
					String	prompt;
					boolean	is_pw;
					
					Object	res = null;
					
					try{	
						boolean	closedown;
						
						synchronized( I2PHelperConsole.this ){
							
							closedown	= destroyed;
							prompt		= request_prompt;
							is_pw		= request_is_pw;
						}
						
						if ( closedown ){
							
							res = null;
							
						}else if ( is_pw ){
							
							res = readPasswordSupport( prompt );
												
						}else{
							
							res = readLineSupport( prompt );
							
						}
					}catch( IOException e ){
						
						res = e;
						
					}catch( Throwable e ){
						
						res = new IOException( Debug.getNestedExceptionMessage( e ));
						
					}finally{
						
						synchronized( I2PHelperConsole.this ){
							
							if ( destroyed ){
								
								reply = null;
								
							}else{
								
								reply	= res;
							}
						}
						
						reply_sem.release();
					}
				}
			}
		}.start();
	}
	
	public String
	readLine(
		String	prompt )
	
		throws IOException
	{
		return((String)readSupport( prompt, false ));
	}
	
	public char[]
	readPassword(
		String	prompt )
	
		throws IOException
	{
		return((char[])readSupport( prompt, true ));
	}
	
	public Object
	readSupport(
		String	prompt,
		boolean	is_pw )
	
		throws IOException
	{
		synchronized( this ){
			
			if ( request_prompt != null ){
				
				throw( new IOException( "Concurrent access not supported" ));
			}
			
			request_prompt	= prompt;
			request_is_pw	= is_pw;
		}
		
		request_sem.release();
		
		reply_sem.reserve();
		
		try{
			
			if ( reply instanceof IOException ){
				
				throw((IOException)reply);
			}
			
			return( reply );
			
		}finally{
			
			synchronized( this ){

				request_prompt = null;
			}
		}
	}
	
	public String
	readLineSupport(
		String	prompt )
	
		throws IOException
	{
		if ( console != null ){
						
			return( ((Console)console).readLine("%s",prompt));
		
		}else{
			
			System.out.println( prompt );
			
			return( in.readLine());
		}
	}
	
	public char[]
	readPasswordSupport(
		String	prompt )
	
		throws IOException
	{
		if ( console != null ){
			
    		return( ((Console)console).readPassword("%s", prompt ));
    		
		}else{
						
			return( readLineSupport(prompt).toCharArray());
		}
	}
	
	public void
	close()
	{
		synchronized( this ){

			destroyed	= true;
			reply		= null;
		}
		
		request_sem.releaseForever();
		reply_sem.releaseForever();
	}
}
