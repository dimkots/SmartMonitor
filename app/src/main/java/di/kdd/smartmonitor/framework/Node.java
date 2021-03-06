package di.kdd.smartmonitor.framework;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import di.kdd.smartmonitor.framework.Acceleration.AccelerationAxis;
import di.kdd.smartmonitor.framework.ISmartMonitor.Tag;
import di.kdd.smartmonitor.framework.exceptions.TagException;

import android.util.Log;

public abstract class Node extends Thread {
	protected DistributedSystem ds;
	
	/* Modal frequencies for each Axis */
	
	protected List<Float> xAxisFrequencies = new ArrayList<Float>();
	protected List<Float> yAxisFrequencies = new ArrayList<Float>();
	protected List<Float> zAxisFrequencies = new ArrayList<Float>();
	protected PeerData peerData = new PeerData();

	private static final String TAG = "Node";
	
	
	
	
	public List<Float> getAxisFrequencies(AccelerationAxis axis) {
		switch(axis) {
		case X:
			return xAxisFrequencies;
		case Y:
			return yAxisFrequencies;
		case Z:
			return zAxisFrequencies;
		default:
			return null;
		}		
	}
	
	/* Sets the global modal frequencies of the system. 
	 * Must be called from the DataAggregatorAsyncTask, after 
	 * receiving the node peaks and computing the global frequencies 
	 */ 

	protected void setModalFrequencies(AccelerationAxis axis, List<Float> frequencies) {
		switch(axis){
		case X:
			xAxisFrequencies = frequencies;
			break;
		case Y:
			yAxisFrequencies = frequencies;
			break;
		case Z:
			zAxisFrequencies = frequencies;
			break;
		}
	}
	
	
	
	
	public abstract void disconnect();
	
	public abstract boolean isMaster();

	public String getLowestIP() {
		return peerData.getLowestIP();
	}
	
	public String getNodeIP() {
		return peerData.getNodeIP();
	}

	public void forgetMasterIP() {
		peerData.forgetMasterIP();
	}
	
	public void setMasterIP(String ip) {
		peerData.setMasterIP(ip);
	}
	
	/***
	 * Given an open socket and a message, sends the message
	 * @param socket The open socket to send the message 
	 * @param message The message to send
	 * @throws IOException When the socket is not open
	 */
	
	protected static void send(Socket socket, Message message) throws IOException {
		Log.i(TAG, "Sending: " + message.toString());

		ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
		out.writeObject(message);
		out.flush();
	}
	
	/***
	 * Give an initialized socket, it returns its input stream
	 * @param socket The socket to get the input stream of
	 * @return The socket's input stream
	 * @throws IOException
	 */
	
	protected static Message receive(Socket socket) throws IOException {
		Log.i(TAG, "Receiving from " + socket.getInetAddress());
		
		try {
			ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
			return (Message) in.readObject();
		}
		catch(ClassNotFoundException e) {
			return null;
		}
	}
	
	/***
	 * Receives a message from a connected socket and checks the tag of the message
	 * @param tag The desired tag
	 * @param socket The connected socket
	 * @return The message that was read from the socket
	 * @throws IOException Socket failure
	 * @throws TagException When the desired tag is not the same with the tag of 
	 * the received message
	 */
	
	protected static Message receive(Socket socket, Tag tag) throws IOException, TagException {
		Message message;
		ObjectInputStream in;
		
		Log.i(TAG, "Receiving from " + socket.getInetAddress() + " with desired Tag: " + tag.toString());
		
		try {
			in = new ObjectInputStream(socket.getInputStream());
			message = (Message) in.readObject();
	
			/* Check received message Tag */
			
			if(message.getTag() != tag) {
				throw new TagException();
			}
			
			return message;
		}
		catch(ClassNotFoundException e) {
			return null;
		}

	}

}
