package di.kdd.smartmonitor.protocol;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import android.util.Log;

import di.kdd.smartmonitor.Acceleration;
import di.kdd.smartmonitor.AccelerationsSQLiteHelper;
import di.kdd.smartmonitor.Complex;
import di.kdd.smartmonitor.FFT;
import di.kdd.smartmonitor.IObservable;
import di.kdd.smartmonitor.IObserver;
import di.kdd.smartmonitor.ISampler;
import di.kdd.smartmonitor.Acceleration.AccelerationAxis;
import di.kdd.smartmonitor.protocol.ConnectAsyncTask.ConnectionStatus;
import di.kdd.smartmonitor.protocol.exceptions.ConnectException;
import di.kdd.smartmonitor.protocol.exceptions.DatabaseException;
import di.kdd.smartmonitor.protocol.exceptions.MasterException;
import di.kdd.smartmonitor.protocol.exceptions.SamplerException;

public class DistributedSystem implements ISmartMonitor, IObservable, IObserver {
	private ISampler sampler;
	private List<IObserver> observers = new ArrayList<IObserver>();

	private Node node;
	
	private long startSamplingTimestamp;
	private long stopSamplingTimestamp;
	
	/* States */
	
	private boolean isConnected;
	private boolean isSampling;
	
	private static final String TAG = "distributed system";
	
	private AccelerationsSQLiteHelper db;
	
	public void setDatabase(AccelerationsSQLiteHelper db) {
		this.db = db;
	}
	
	/* Singleton implementation */

	private static DistributedSystem ds;
	
	private DistributedSystem() {
	}
	
	public static DistributedSystem getInstance() {
		if(ds == null) {
			ds = new DistributedSystem();
		}
		
		return ds;
	}

	/* IObservable implementation */
	
	@Override
	public void unsubscribe(IObserver observer) {
		observers.remove(observer);
	}

	@Override
	public void subscribe(IObserver observer) {
		observers.add(observer);		
	}
	
	@Override
	public void notify(String message) {
		for(IObserver observer : observers) {
			observer.update(message);
		}
	}

	/* ISmartMonitor implementation */
		
	@Override
	public void connect() {
		Log.i(TAG, "Connecting");
		
		ConnectAsyncTask connectTask = new ConnectAsyncTask();
		connectTask.subscribe(this);
		connectTask.execute();
	}

	@Override
	public void connectAsMaster() {
		Log.i(TAG, "Connecting as Master");
		
		node = new MasterNode(this); 
		isConnected = true;

		
		notify("Connected as Master");			
	}

	@Override
	public void connectAt(String ip) {
		Log.i(TAG, "Connecting as Peer at " + ip);

		ConnectAsyncTask connectTask = new ConnectAsyncTask(ip);
		connectTask.subscribe(this);
		connectTask.execute();
	}	
	
	@Override
	public boolean isConnected() {
		return isConnected;
	}
	
	@Override
	public void disconnect() {
		Log.i(TAG, "Disconnecting");
		
		node.disconnect();
		node = null;
		isConnected = false;
		
		notify("Disconnected");
	}

	@Override
	public void setSampler(ISampler sampler) {
		this.sampler = sampler;
	}
	
	@Override
	public void startSampling() throws MasterException, ConnectException, SamplerException, IOException {		
		if(node == null) {
			throw new ConnectException();
		}

		if(node.isMaster() == false) {
			throw new MasterException();
		}
		
		if(sampler == null) {
			throw new SamplerException();
		}

		((MasterNode) node).startSampling();			
		
		sampler.startSamplingService();
		isSampling = true;
		startSamplingTimestamp = System.currentTimeMillis();
		
		notify("Started sampling");
	}

	@Override
	public void stopSampling() throws MasterException, ConnectException, SamplerException, IOException {
		if(node == null) {
			throw new ConnectException();
		}

		if(node.isMaster() == false) {
			throw new MasterException();
		}
		
		if(sampler == null) {
			throw new SamplerException();
		}
		
		((MasterNode) node).stopSampling();			
		
		sampler.stopSamplingService();
		isSampling = false;
		stopSamplingTimestamp = System.currentTimeMillis();

		notify("Stoped sampling");
	}
	
	@Override
	public void deleteDatabase() throws MasterException, ConnectException {
		if(node == null) {
			throw new ConnectException();
		}

		if(node.isMaster() == false) {
			throw new MasterException();
		}

		((MasterNode) node).deleteDatabase();
		db.deleteDatabase();
		
		notify("Deleted database");
	}
	
	@Override
	public void dumpDatabase() throws MasterException, ConnectException {
		if(node == null) {
			throw new ConnectException();
		}

		if(node.isMaster() == false) {
			throw new MasterException();
		}

		((MasterNode) node).dumpDatabase();
		db.dumpToFile();
		
		notify("Dumped database");		
	}
	
	@Override
	public boolean isSampling() {
		return isSampling;
	}
		
	@Override
	public void computeModalFrequencies() throws MasterException, ConnectException {
		if(node == null) {
			throw new ConnectException();
		}

		if(node.isMaster() == false) {
			throw new MasterException();
		}
		
		((MasterNode) node).computeModalFrequencies();
		
		notify("Computing modal frequencies");
	}

	@Override
	public boolean isMaster() {
		return (node != null && node.isMaster());
	}

	@Override
	public String getMasterIP() {
		return (node != null) ? "None" : node.getMasterIP();
	}
	
	private Socket joinSocket;
	
	/***
	 * Allow to the ConnectAsyncTask to pass the connected socket
	 * to the master, in order to join the system
	 * @param joinSocket The connected to the JOIN port socket
	 */
	
	protected void setPeerJoinSocket(Socket joinSocket) {
		this.joinSocket = joinSocket;
	}

	/* IObserver implementation */
	
	@Override
	public void update(String message) {
		switch(ConnectionStatus.valueOf(message)) {
		case ConnectedAsMaster:
			Log.i(TAG, "Connected as Master");

			node = new MasterNode(this);		
			isConnected = true;
			
			notify("Connected as Master");		
			break;
		case ConnectedAsPeer:
			Log.i(TAG, "Connected as Peer");

			node = new PeerNode(this, joinSocket);
			isConnected = true;
			
			notify("Connected as Peer");		
			break;			
		case FailedToConnect:
			Log.e(TAG, "Failed to connect as Peer");
			notify("Failed to connect as Peer");
			break;
		default:				
		}		
	}
	
	/* Methods that should be called form the peer node, when he receives a command */
	
	protected void startSamplngCommand() {
		if(sampler != null) {
			sampler.startSamplingService();
			isSampling = true;
			startSamplingTimestamp = System.currentTimeMillis();
			
			notify("Started sampling");
		}
	}
	
	protected void stopSamplingCommand() {
		if(sampler != null && isSampling) {
			sampler.stopSamplingService();
			isSampling = false;
			stopSamplingTimestamp = System.currentTimeMillis();
			
			notify("Stoped sampling");
		}
	}	
	
	protected void deleteDatabaseCommand() {		
		if(db != null) {
			db.deleteDatabase();
			
			notify("Deleted database");
		}
	}
	
	protected void dumpDatabaseCommand() {
		if(db != null) {
			db.dumpToFile();
			
			notify("Dumped database");
		}		
	}	
	
	private float computeSamplingFrequencyInSample(long from, long to, List<Acceleration> accelerations) {		
		return (float) accelerations.size() / ((float) (to - from) / 1000);		
	}
	
	private List<Float> computeModalFrequenciesInAxis(long from, long to, AccelerationAxis axis) {		
		List<Float> modalFrequencies = new ArrayList<Float>();

		try {
			float samplingFrequency;
			Complex[] fftResults = new Complex[FFT_Length];
			Double[] output = new Double[FFT_Length / 2];
			List<Acceleration> accelerations = db.getAccelerationsIn(from, to, axis);

			/* Create FFT's input (Complex number with Real part the acceleration and zero Imaginary part */

			for(int i = 0; i < FFT_Length; i++) {
				fftResults[i] = new Complex(accelerations.get(i).getAcceleration(), 0);
			}
			
			/* Compute the FFT output */
			
			fftResults = FFT.fft(fftResults);

			for(int i = 0; i < FFT_Length / 2; i++) {
				output[i] = Math.sqrt(Math.pow(fftResults[i].re(), 2) + Math.pow(fftResults[i].im(), 2));
			}
			
			/* Find peaks in the FFT's output, within each window */
			
			int numberOfWindows = output.length / PEAK_PEEKING_WINDOW;
			List<Integer> maxIndices = new ArrayList<Integer>();
			
			for(int i = 0; i < numberOfWindows; i++) {

				/* Find the index of the window's max value */
				
				int windowStart = PEAK_PEEKING_WINDOW * i;
				int windowEnd = windowStart + PEAK_PEEKING_WINDOW;
				
				int windowMaxIndex = 0;
				double windowMaxValue = 0.0f;
				
				for(int j = windowStart; j <= windowEnd; j++) {
					if(output[j] > windowMaxValue) {
						windowMaxIndex = j;
						windowMaxValue = output[j];
					}
				}

				maxIndices.add(windowMaxIndex);
			}
			
			/* Keep the top NO_PEAKS indices */
			
			Collections.sort(maxIndices);
			
			while(maxIndices.size() > NO_PEAKS) {
				maxIndices.remove(0);
			}
			
			/* Convert index values of the peaks to frequency bins */

			samplingFrequency = computeSamplingFrequencyInSample(from, to, accelerations);

			for(Integer index : maxIndices) {
				modalFrequencies.add((float) (index * samplingFrequency) / FFT_Length);
			}
						
		} catch (Exception e) {
			Log.e(TAG, "Error while retrieving acclerations of axis " + axis.toString() + 
												" between timestamps: " + e.getMessage());
			e.printStackTrace();
		}
		
		return modalFrequencies;
	}
	
	protected List<Float> computeModalFrequenciesCommand() throws DatabaseException {
		ArrayList<Float> modalFrequencies = new ArrayList<Float>();
		
		if(db == null) {
			notify("Cannot compute modal frequencies, database is not set");
			return null;
		}
		
		/* Compute the modal frequencies of each axis, within the period of sampling */
		
		modalFrequencies.addAll(computeModalFrequenciesInAxis(startSamplingTimestamp, stopSamplingTimestamp, AccelerationAxis.X));
		modalFrequencies.addAll(computeModalFrequenciesInAxis(startSamplingTimestamp, stopSamplingTimestamp, AccelerationAxis.Y));
		modalFrequencies.addAll(computeModalFrequenciesInAxis(startSamplingTimestamp, stopSamplingTimestamp, AccelerationAxis.Z));
				
		notify("Computed modal frequencies");
		
		return modalFrequencies;
	}
}
