package com.sixin.speex;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.RecoverySystem.ProgressListener;
import android.util.Log;

/**
 * 采用Jspeex方案，首先解包，从ogg里面接出来，然后使用speex decode将speex转为wav数据并进行播放
 * 
 * @author Gauss
 */
public class SpeexDecoder {

	protected Speex speexDecoder;
	private boolean paused = false;
	private String errmsg = null;
	private List<ProgressListener> listenerList = new ArrayList<ProgressListener>();
	private File srcPath;
	private AudioTrack track;

	public SpeexDecoder(File srcPath) throws Exception {
		this.srcPath = srcPath;
	}

	private void initializeAndroidAudio(int sampleRate) throws Exception {
		if (track != null) {
			return;
		}
		int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

		if (minBufferSize < 0) {
			throw new Exception("Failed to get minimum buffer size: " + Integer.toString(minBufferSize));
		}
		track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize, AudioTrack.MODE_STREAM);
	}

	public void addOnMetadataListener(ProgressListener l) {
		listenerList.add(l);
	}

	public synchronized void setPaused(boolean paused) {
		this.paused = paused;
	}

	public synchronized boolean isPaused() {
		return paused;
	}

	public String getErrmsg() {
		return errmsg;
	}

	public void decode() throws Exception {
		errmsg = null;
		byte[] header = new byte[2048];
		byte[] payload = new byte[65536];
		final int OGG_HEADERSIZE = 27;
		final int OGG_SEGOFFSET = 26;
		final String OGGID = "OggS";
		int segments = 0;
		int curseg = 0;
		int bodybytes = 0;
		int decsize = 0;
		int packetNo = 0;
		// construct a new decoder
		speexDecoder = new Speex();
		speexDecoder.init();
		// open the input stream
		RandomAccessFile dis = new RandomAccessFile(srcPath, "r");

		int origchksum;
		int chksum;
		try {

			// read until we get to EOF
			while (true) {
				if (Thread.interrupted()) {
					dis.close();
					track.stop();
					track.release();
					return;
				}

				while (this.isPaused()) {
					track.stop();
					track.release();
					// Thread.sleep(100);
				}

				// read the OGG header
				dis.readFully(header, 0, OGG_HEADERSIZE);
				origchksum = readInt(header, 22);
				readLong(header, 6);
				header[22] = 0;
				header[23] = 0;
				header[24] = 0;
				header[25] = 0;
				chksum = OggCrc.checksum(0, header, 0, OGG_HEADERSIZE);

				// make sure its a OGG header
				if (!OGGID.equals(new String(header, 0, 4))) {
					System.err.println("missing ogg id!");
					errmsg = "missing ogg id!";
					return;
				}

				/* how many segments are there? */
				segments = header[OGG_SEGOFFSET] & 0xFF;
				dis.readFully(header, OGG_HEADERSIZE, segments);
				chksum = OggCrc.checksum(chksum, header, OGG_HEADERSIZE, segments);

				/* decode each segment, writing output to wav */
				for (curseg = 0; curseg < segments; curseg++) {

					if (Thread.interrupted()) {
						dis.close();
						track.stop();
						track.release();
						return;
					}

					while (this.isPaused()) {
						track.stop();
						track.release();
						// Thread.sleep(100);
					}

					/* get the number of bytes in the segment */
					bodybytes = header[OGG_HEADERSIZE + curseg] & 0xFF;
					if (bodybytes == 255) {
						System.err.println("sorry, don't handle 255 sizes!");
						return;
					}
					dis.readFully(payload, 0, bodybytes);
					chksum = OggCrc.checksum(chksum, payload, 0, bodybytes);

					/* decode the segment */
					/* if first packet, read the Speex header */
					if (packetNo == 0) {
						if (readSpeexHeader(payload, 0, bodybytes, true)) {
							packetNo++;
						} else {
							packetNo = 0;
						}
					} else if (packetNo == 1) { // Ogg Comment packet
						packetNo++;
					} else {

						/* get the amount of decoded data */
						short[] decoded = new short[160];
						if ((decsize = speexDecoder.decode(payload, decoded, 160)) > 0) {
							track.write(decoded, 0, decsize);
							float maxVol = AudioTrack.getMaxVolume();
							track.setStereoVolume(maxVol, maxVol);// 设置当前音量大小
							track.play();
						}
						packetNo++;
					}
				}
				if (chksum != origchksum)
					throw new IOException("Ogg CheckSums do not match");
			}
		} catch (Exception e) {
		} finally {
			try {
				if (null != track && track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
					track.stop();
					track.release();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		dis.close();
	}

	/**
	 * Reads the header packet.
	 * 
	 * <pre>
	 *  0 -  7: speex_string: "Speex   "
	 *  8 - 27: speex_version: "speex-1.0"
	 * 28 - 31: speex_version_id: 1
	 * 32 - 35: header_size: 80
	 * 36 - 39: rate
	 * 40 - 43: mode: 0=narrowband, 1=wb, 2=uwb
	 * 44 - 47: mode_bitstream_version: 4
	 * 48 - 51: nb_channels
	 * 52 - 55: bitrate: -1
	 * 56 - 59: frame_size: 160
	 * 60 - 63: vbr
	 * 64 - 67: frames_per_packet
	 * 68 - 71: extra_headers: 0
	 * 72 - 75: reserved1
	 * 76 - 79: reserved2
	 * </pre>
	 * 
	 * @param packet
	 * @param offset
	 * @param bytes
	 * @return
	 * @throws Exception
	 */
	private boolean readSpeexHeader(final byte[] packet, final int offset, final int bytes, boolean init) throws Exception {
		if (bytes != 80) {
			return false;
		}
		if (!"Speex   ".equals(new String(packet, offset, 8))) {
			return false;
		}
		String version = new String(packet, offset + 8, 20);
		int version_id = readInt(packet, offset + 28);
		int header_size = readInt(packet, offset + 32);
		int sampleRate = readInt(packet, offset + 36);
		int mode = packet[40 + offset] & 0xFF;
		int mode_bitstream_version = readInt(packet, offset + 44);
		int channels = readInt(packet, offset + 48);
		int bitrate = readInt(packet, offset + 52);
		int frameSize = readInt(packet, offset + 56);
		int vbr = readInt(packet, offset + 60);
		int nframes = readInt(packet, offset + 64);
		int extra_headers = readInt(packet, offset + 68);
		int reserved1 = readInt(packet, offset + 72);
		int reserved2 = readInt(packet, offset + 76);
//		Log.e("tag", "mode=" + mode + " sampleRate==" + sampleRate + " channels=" + channels + "nframes=" + nframes + "framesize=" + frameSize);
		Log.e("test", "version=" + version
				+ "    version_id=="+ version_id
				+ "    header_size=="+ header_size
				+ "    sampleRate=="+ sampleRate
				+ "    mode=="+ mode
				+ "    mode_bitstream_version=="+ mode_bitstream_version
				+ "    channels=="+ channels
				+ "    frameSize=="+ frameSize
				+ "    bitrate=="+ bitrate
				+ "    vbr=="+ vbr
				+ "    nframes=="+ nframes
				+ "    extra_headers=="+ extra_headers
				+ "    reserved1=="+ reserved1
				+ "    reserved2=="+ reserved2
				);
		initializeAndroidAudio(sampleRate);

		if (init) {
			// return speexDecoder.init(mode, sampleRate, channels, enhanced);
			return true;
		} else {
			return true;
		}
	}

	protected static int readInt(final byte[] data, final int offset) {
		/*
		 * no 0xff on the last one to keep the sign
		 */
		return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8) | ((data[offset + 2] & 0xff) << 16) | (data[offset + 3] << 24);
	}

	protected static long readLong(final byte[] data, final int offset) {
		/*
		 * no 0xff on the last one to keep the sign
		 */
		return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8) | ((data[offset + 2] & 0xff) << 16) | ((data[offset + 3] & 0xff) << 24) | ((data[offset + 4] & 0xff) << 32)
				| ((data[offset + 5] & 0xff) << 40) | ((data[offset + 6] & 0xff) << 48) | (data[offset + 7] << 56);
	}

	protected static int readShort(final byte[] data, final int offset) {
		/*
		 * no 0xff on the last one to keep the sign
		 */
		return (data[offset] & 0xff) | (data[offset + 1] << 8);
	}

}
