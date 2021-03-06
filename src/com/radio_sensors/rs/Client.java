// Copyright (C) 2013 Olof Hagsand and Robert Olsson
//
// This file is part of Read-Sensors.
//
// Read-Sensors is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// Read-Sensors is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Read-Sensors; see the file COPYING.

package com.radio_sensors.rs;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Bundle;
import android.os.Environment;
import android.os.AsyncTask;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.view.Display;
import android.view.View;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.lang.Thread;
import android.util.Log;
import android.os.Message;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.String;
import java.lang.Object;

public class Client extends RSActivity {
    // Messages
    final public static int ERROR  = -1;         // Something went wrong
    final public static int STATUS = 2;          // Status change
    final public static int STATUS_USB = 3;          // Status change
    final public static int SENSD  = 4;          // Report from sensd server
    final public static int TIMER  = 5;       // Interval timer every 1s (debug)
    final public static int REPLAY = 6;       // Replay all stored sensd data
    final public static int SENSD_CMD  = 10;          // Report arrived from sensd server
    final public static int SENSD_SEND  = 11;          // Send report to sensd gateway
    final public static int SENSD_USB  = 12;          // sensd report from USB

    private static int TIMERINTERVAL = 2000; // interval between sample receives


    private Thread connectthread = null;
    public  Thread usbthread = null;
    public static Client client = null;
    private boolean active = false;           // Activity is active
    
    ConnectSocket connect_cs;                 // Object containing connect-socket
    public static ConnectUSB USB = null; 

    // Debug 
    final static int DEBUG_NONE        = 0;
    final static int DEBUG_REPORT      = 1;
    final static int DEBUG_PLOT        = 2;
    final static int DEBUG_FILTER      = 3;

    public static int debug       = DEBUG_NONE;

    public static Handler ploth = null; // PlotWindow handler
    public static Handler sockh = null; // ConnectSocket handler

    // Textwindow
    private ArrayList<String> report = new ArrayList<String>();
    private int max_samples;

    final private static String TAG = "RS-" + Client.class.getName();
    final public static String FILE = "/RS-GW.txt";
    final public static String FILE2 = "/RS-GW2.txt";
    final public static String URL_GW_LIST = "http://www.radio-sensors.com/app/Read-Sensors/RS-GW.txt";
    final public static String DEMO_GW = "DEMO-WSN radio-sensors.com 1235 # WSN in Upppsala, Sweden\n";

    private int conf_gw;    
    private String[] gw_alias;
    private String gw;

    void set_button_text()
    {
	// Set connected/disconnected button text
	Button buttonConnect = (Button) findViewById(R.id.server_connect);
	Button bt = (Button) findViewById(R.id.gw_select);

	String s1 = get_server_ip();
	String s2 = String.valueOf(get_server_port());
	int len = countLines(gw);
	final String[] row = gw.split("\r\n|\r|\n");
	bt.setText("Hotlist");

	for(int i = 0 ; i < len; i++) {
	    String[] col = row[i].split("\\s");
	    if(col[1].equalsIgnoreCase(s1) && col[2].equals(s2))
		bt.setText(col[0]);

	}
	if(connectthread != null) {
	    buttonConnect.setText("Disconnect");
	    if(gw != null)  {
		bt.setEnabled(false);
	    }
	}
	else {
	    buttonConnect.setText("Connect");
	    bt.setEnabled(true);
	}
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
	super.onCreate(savedInstanceState);
	Log.d("RStrace", "Main onCreate");
	client = this; 
	main = this;

	gw = readFromFile();
	setContentView(R.layout.main);
	pref2running(); 	// Set global values from persistent storage
	set_button_text();

	/* USBConnect introduced in SDK version 12 */
	Log.d("RStrace", "SDK version="+android.os.Build.VERSION.SDK_INT);
	if (android.os.Build.VERSION.SDK_INT >= 12) {
	    // only for gingerbread and newer versions
	    if(connect_usb() == true) {
		Toast.makeText(this, "USB connect at " +  Integer.toString(USBSettings.serial_speed) + " bps", 
			       Toast.LENGTH_SHORT).show();
	    }
	}

	// Start periodic timer
	Message message = Message.obtain();
	message.what = Client.TIMER;
	mHandler.sendMessageDelayed(message, TIMERINTERVAL);
    }

    // This is called on resize
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
	super.onConfigurationChanged(newConfig);

	setContentView(R.layout.main);
	Log.d("RStrace", "Main onConfigurationChanged");

	// Set edit text fields from prefs
	EditText et = (EditText) findViewById(R.id.server_ip);
	et.setText(get_server_ip());
	et = (EditText) findViewById(R.id.server_port);
	et.setText(""+get_server_port());

	set_button_text();
	textupdate(); // Update text-sensd reports in window
    }

    // Called when 'connect/disconnect' button is clicked
    public void onClickConnect(View view) {
	EditText et_srv = (EditText) findViewById(R.id.server_ip);
	EditText et_port = (EditText) findViewById(R.id.server_port);

	if(connectthread != null) {
	  Toast.makeText(this, "Disconnecting gateway..", Toast.LENGTH_SHORT).show();
	  disconnect();
	  return;
	}

	set_server_ip(et_srv.getText().toString());
	set_server_port(Integer.parseInt(et_port.getText().toString()));
	connect(get_server_ip(), get_server_port());
	set_button_text();

	Button buttonConnect = (Button) findViewById(R.id.server_connect);
	buttonConnect.setText("Waiting");

    }

    private static int countLines(String str){
	//String[] lines = str.split("\r\n|\r|\n");
	String[] lines = str.split("\r\n|\r|\n");
	return  lines.length;
    }

    /*
     * onGWSelect
     * Called when 'hotlist' button is clicked in pref.xml
     * Build a dialogue menu of radio chans and select one
     */

    public void onGWSelect(View view) {
	AlertDialog.Builder dia = new AlertDialog.Builder(this);
	dia.setTitle("Select Gateway");

	int i;
	int len = countLines(gw);
	gw_alias = new String[len];

	Log.e(TAG, "onGWSelect: 1");

	//	int i = 0;
	//gw_alias[i++] = "Add ";
	//gw_alias[i++] = "Remove ";

	final String[] row = gw.split("\r\n|\r|\n");

	for(i = 0 ; i < len; i++) {
	    String[] col = row[i].split("\\s");
	    gw_alias[i] = col[0];
	}

	dia.setItems(gw_alias, new DialogInterface.OnClickListener() {
		public void onClick(DialogInterface dialog, int which) {
		    Resources res = getResources();
		    final Button bt = (Button) findViewById(R.id.gw_select);
		    bt.setText(gw_alias[which]);
		    String[] s = row[which].split("\\s");
		    EditText et = (EditText) findViewById(R.id.server_ip);
		    set_server_ip(s[1]);
		    et.setText(get_server_ip());
		    et = (EditText) findViewById(R.id.server_port);
		    set_server_port(Integer.parseInt(s[2]));
		    et.setText(""+get_server_port());
		}
	    });
	dia.setInverseBackgroundForced(true);
	dia.create();
	dia.show();
    }

    protected void onStart(){
	super.onStart();

	// Set-up default values from running
	EditText et = (EditText) findViewById(R.id.server_ip);
	et.setText(get_server_ip());
	et = (EditText) findViewById(R.id.server_port);
	et.setText(""+get_server_port());

	Log.d("RStrace", "Main onStart");
    }

    protected void onResume(){
	super.onResume();
	active = true;
	textupdate(); // Update text-sensd reports in window
	Log.d("RStrace", "Main onResume");
    }

    protected void onPause(){
	super.onPause();
	active = false;
	Log.d("RStrace", "Main onPause");
    }

    protected void onStop(){  // This is called when starting another activity
	super.onStop();

	Log.d("RStrace", "Main onStop");
    }

    protected void onDestroy()	{
	super.onDestroy();
	disconnect(); // Kills connect thread
	Log.d("RStrace", "Main onDestroy");
    }


    // This is code for options menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
	MenuInflater inflater = getMenuInflater();
	inflater.inflate(R.layout.main_menu, menu);
	return true;
    }	

    /*
      Called every time right before the option menu is shown.
      use this method to efficiently enable/disable items or 
      otherwise dynamically modify the contents. 
    */

    @Override
	public boolean onPrepareOptionsMenu(Menu menu) {

	MenuItem item = menu.findItem(R.id.usb_mote_conf);

	if(usbthread != null) 
	    item.setEnabled(true);
	else 
	    item.setEnabled(false);

	return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
	// Handle item selection
	switch (item.getItemId()) {
	case R.id.about:
	    AboutBox.Show(this);
	    return true;
	case R.id.prefs:
	    toActivity("PrefWindow");
	    return true;
	case R.id.plot:
	    toActivity("PlotWindow");
	    return true;
	case R.id.web:
	    toActivity("Web");
	    return true;
	case R.id.forward_conf:
	    toActivity("Forward");
	    return true;
	case R.id.share_screen:
	    View v1 = getWindow().getDecorView().getRootView();
	    main.shareScreen(v1);
	    return true;
	case R.id.usb_settings_conf:
	    toActivity("USBSettings");
	    return true;
	case R.id.usb_mote_conf:
	    toActivity("ConfWindow");
	    return true;
	default:
	    return super.onOptionsItemSelected(item);
	}
    }


private void writeToFile(String data) {
	try {
		File myFile = new File( Environment.getExternalStorageDirectory() + FILE);
		myFile.createNewFile();
		FileOutputStream fOut = new FileOutputStream(myFile);
		OutputStreamWriter myOutWriter = new OutputStreamWriter(fOut);
		myOutWriter.append(data);
		myOutWriter.close();
		fOut.close();
	    } catch (Exception e) {
            Log.e(TAG, "File write failed: " + e.toString());
	}
    }
 
    private String readFromFile() {
        String ret = "";
	try {
	    File file = new File( Environment.getExternalStorageDirectory() + FILE);

	    if( ! file.exists())  {
		Log.e(TAG, "RS File not found: ");
		ret = DEMO_GW;
		writeToFile(ret);
		new SlowOperation().execute(URL_GW_LIST);
	    }

	    FileInputStream fIn = new FileInputStream(file);
	    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(fIn));
	    String str = "";
	    String newstr = "";
	    StringBuilder stringBuilder = new StringBuilder();
	    int idx, len;
	    String ss;

	    while ( ( str = bufferedReader.readLine()) != null ) {
		// Ignore comments
		if (null != str && str.length() > 0 ) {
		    idx = str.indexOf("#");
		    if (idx == -1)
			stringBuilder.append(str + "\n");
		    else {
			len = str.length();
			ss = str.substring(0, idx);
			if(ss.length() > 0) 
			    stringBuilder.append(ss + "\n");
		    }
		}  
	    }
	    fIn.close();
	    ret = stringBuilder.toString();
	}
        catch (FileNotFoundException e) {
            Log.e(TAG, "RS File not found: " + e.toString());

	    new SlowOperation().execute(URL_GW_LIST);

        } catch (IOException e) {
            Log.e(TAG, "Can't read file: " + e.toString());
        }
	return ret;
    }

    // Connect to a server: create connectsocket object, a thread and start it.
    private void connect(String srv_ip, int srv_port) {
	if(connectthread != null){
	    // Should not happen
	    return;
	}
	connect_cs = new ConnectSocket(srv_ip, srv_port, mHandler); // Here add parameters
	connectthread = new Thread(connect_cs, "Connect Socket");
	connectthread.start();
    }

    private boolean connect_usb() {
	if(usbthread != null){
	    // Should not happen
	    return true;
	}

	USB = new ConnectUSB(mHandler);
	if( USB.connect() == false )
	    return false;

	usbthread = new Thread(USB, "USB connect");
	usbthread.start();
	return true;
    }

    // Post an interrupt to the connect thread and call its kill method
    private void disconnect_usb() {

	if(usbthread != null) {
	    try{
		USB.kill();
		usbthread.interrupt();
		// usbthread is set to null only when detected by mHandler
	    }
	    catch (Exception e1) {
		e1.printStackTrace();
	    }
	}
    }

    private void disconnect() {

	if(connectthread != null) {
	    try{
		connect_cs.kill();
		connectthread.interrupt();
		// connectthread is set to null only when detected by mHandler
	    }
	    catch (Exception e1) {
		e1.printStackTrace();
	    }
	}
    }

    // Draw reports in text window. Scroll to bottom of texts.
    private void textupdate(){
        TextView tv = (TextView) findViewById(R.id.text);
        ScrollView sv = (ScrollView) findViewById(R.id.scroll);

        String text = "";
        for (String str:report){
	    text += str + System.getProperty("line.separator");
        }
        tv.setText(text);
        sv.smoothScrollTo(0, tv.getBottom());
    }

    // Messages comes in from socket-handler due to sensd input or error
    public final Handler mHandler = new Handler() {
	    public void handleMessage(Message msg ) {
		Message message;
		switch (msg.what) {
		case Client.SENSD: // New report from sensd			
		case Client.SENSD_USB: // New report via USB			

		    String s = (String)msg.obj;
		    if (s.length()>0)
			report.add(s) ;
		    if (report.size() >= get_max_samples())
			report.remove(0); //remove first line if max
		    if (ploth != null)
			message(ploth, Client.SENSD, s);
		    if (active)
			textupdate();         // update text if active

		    // Avoid loops
		    if (msg.what == Client.SENSD_USB && usbthread != null && connectthread != null) {
			String fw_domain = "DOMAIN=" + get_domain();
			//String s1 = s.substring(0, s.length() - 1); // Remove last char
			s = s.replace("DOMAIN=", fw_domain);
			message(sockh, Client.SENSD_SEND, s);
		    }

		    break;

		case Client.ERROR: // Something went wrong
		    Log.d("RStrace", "Error"+(String)msg.obj);
		    Toast.makeText(Client.client, "Error: "+(String)msg.obj, Toast.LENGTH_LONG).show();		    
		    break;

		case Client.STATUS: // Connect status changed
		    Integer stat = (Integer)msg.obj;
		    Button buttonConnect = (Button) findViewById(R.id.server_connect);
		    Button bt = (Button) findViewById(R.id.gw_select);

		    Log.d("RStrace", "Client.Status="+stat);
		    if (stat != null){
			if (stat.equals(0)){
			    if (connectthread!= null &&
				connectthread.getState().equals(Thread.State.TERMINATED)){
				Log.d("RStrace", "STATUS: Thread TERMINATED");
				connectthread = null;
			    }
			    connectthread = null;
			    buttonConnect.setText("Connect");			    
			    bt.setEnabled(true);
			    Toast.makeText(Client.client, "Disconnected", Toast.LENGTH_SHORT).show();		    
			}
			else if (stat.equals(1))
			    buttonConnect.setText("Disconnect");			    
		    }
		    break;
		case Client.STATUS_USB: // Connect status changed
		    stat = (Integer)msg.obj;
		    buttonConnect = (Button) findViewById(R.id.server_connect);

		    Log.d("RStrace", "Client.Status="+stat);
		    if (stat != null){
			if (stat.equals(0)){
			    if (usbthread!= null &&
				usbthread.getState().equals(Thread.State.TERMINATED)){
				Log.d("RStrace", "STATUS: USB Thread TERMINATED");
				usbthread = null;
			    }
			    usbthread = null;
			    Toast.makeText(Client.client, "USB Disconnected", Toast.LENGTH_SHORT).show();
			}
			else if (stat.equals(1)) {
			    Toast.makeText(Client.client, "USB Connected", Toast.LENGTH_SHORT).show();
			}
		    }
		    break;
		case Client.TIMER: // Periodic timer 

		    // Sanity check: close terminated thread if not by other means
		    if (connectthread != null){
			if (connectthread.getState().equals(Thread.State.TERMINATED)){
			    Log.d("RStrace", "TIME: Thread TERMINATED");
			    connectthread = null;
			}
		    }

		    message = Message.obtain();
		    message.what = Client.TIMER;
		    mHandler.sendMessageDelayed(message, TIMERINTERVAL);
		    break;
		case Client.REPLAY: // Replay all sensd data
		    if (ploth != null)
			for (String str:report)
			    message(ploth, Client.SENSD, str);
		    break;
		}
	    }
	};

    private Boolean get_GW_list_from_URL(String urls) {    

	URL url = null;
	InputStream input = null;

	try{
	    url = new URL(urls);
	}
	catch(MalformedURLException e) {
            Log.e(TAG, "URL failed: " + e.toString());
	    return false;
	}
	try {
	    input = url.openStream();
	}
	catch(IOException e) {
            Log.e(TAG, "get_GW_list_from_URL failed: " + e.toString());
	    return false;
	}
	try {
	    File myFile = new File( Environment.getExternalStorageDirectory() + FILE);
	    myFile.createNewFile();

	    OutputStream output = new FileOutputStream (myFile);

	    try {
		byte[] buffer = new byte[1024];
		int bytesRead = 0;
		while ((bytesRead = input.read(buffer, 0, buffer.length)) >= 0) {
		    output.write(buffer, 0, bytesRead);
		}
		output.close();
		input.close();
	    }
	    catch(IOException e) {
		Log.e(TAG, "URL failed: " + e.toString());
		return false;
	    }
	}
	catch(IOException e) {
	    Log.e(TAG, "URL failed: " + e.toString());
	    return false;
	}
	return true;
    }

    /*
      Private subclass for dealing with tasks not allowed in UI.
      For example getting the GW hotlist from a server.
    */

    private class SlowOperation  extends AsyncTask <String, Void, Void> {
        protected Void doInBackground(String... urls) {
                 
	    get_GW_list_from_URL(urls[0]);
             
            return null;
        }

	protected void onPreExecute() {
        }

	protected void onPostExecute(Void unused) {
	    Log.e(TAG, "onPostExecute");
	    gw = readFromFile();
        }
    }
};
