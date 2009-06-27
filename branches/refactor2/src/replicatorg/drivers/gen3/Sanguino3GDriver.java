/*
 Sanguino3GDriver.java

 This is a driver to control a machine that uses the Sanguino with 3rd Generation Electronics.

 Part of the ReplicatorG project - http://www.replicat.org
 Copyright (c) 2008 Zach Smith

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software Foundation,
 Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package replicatorg.drivers.gen3;

import java.io.IOException;
import java.util.EnumSet;
import java.util.logging.Level;

import javax.vecmath.Point3d;

import org.w3c.dom.Node;

import replicatorg.app.Base;
import replicatorg.app.Preferences;
import replicatorg.app.Serial;
import replicatorg.app.TimeoutException;
import replicatorg.app.exceptions.SerialException;
import replicatorg.app.tools.XML;
import replicatorg.drivers.BadFirmwareVersionException;
import replicatorg.drivers.DriverBaseImplementation;
import replicatorg.drivers.Version;
import replicatorg.machine.model.Axis;
import replicatorg.machine.model.ToolModel;

public class Sanguino3GDriver extends DriverBaseImplementation {
	/**
	 * An enumeration of the available command codes for the three-axis CNC
	 * stage.
	 */
	enum CommandCodeMaster {
		VERSION(0),
		INIT(1),
		GET_BUFFER_SIZE(2),
		CLEAR_BUFFER(3),
		GET_POSITION(4),
		GET_RANGE(5),
		SET_RANGE(6),
		ABORT(7),
		PAUSE(8),
		PROBE(9),
		TOOL_QUERY(10),
		IS_FINISHED(11),

		// QUEUE_POINT_INC(128) obsolete
		QUEUE_POINT_ABS(129),
		SET_POSITION(130),
		FIND_AXES_MINIMUM(131),
		FIND_AXES_MAXIMUM(132),
		DELAY(133),
		CHANGE_TOOL(134),
		WAIT_FOR_TOOL(135),
		TOOL_COMMAND(136),
		ENABLE_AXES(137);
		
		private int code;
		private CommandCodeMaster(int code) {
			this.code = code;
		}
		int getCode() { return code; }
	};

	/**
	 * An enumeration of the available command codes for a tool.
	 */
	enum CommandCodeSlave {
		VERSION(0),
		INIT(1),
		GET_TEMP(2),
		SET_TEMP(3),
		SET_MOTOR_1_PWM(4),
		SET_MOTOR_2_PWM(5),
		SET_MOTOR_1_RPM(6),
		SET_MOTOR_2_RPM(7),
		SET_MOTOR_1_DIR(8),
		SET_MOTOR_2_DIR(9),
		TOGGLE_MOTOR_1(10),
		TOGGLE_MOTOR_2(11),
		TOGGLE_FAN(12),
		TOGGLE_VALVE(13),
		SET_SERVO_1_POS(14),
		SET_SERVO_2_POS(15),
		FILAMENT_STATUS(16),
		GET_MOTOR_1_RPM(17),
		GET_MOTOR_2_RPM(18),
		GET_MOTOR_1_PWM(19),
		GET_MOTOR_2_PWM(20),
		SELECT_TOOL(21),
		IS_TOOL_READY(22);
		
		private int code;
		private CommandCodeSlave(int code) {
			this.code = code;
		}
		int getCode() { return code; }
	};

	/**
	 * An object representing the serial connection.
	 */
	private Serial serial;

	/**
	 * Serial connection parameters
	 */
	String name;
	int rate;
	char parity;
	int databits;
	float stopbits;

	public Sanguino3GDriver() {
		super();

		// This driver only covers v1.X firmware
		minimumVersion = new Version(1,1);
		preferredVersion = new Version(1,1);
		// init our variables.
		setInitialized(false);

		// some decent default prefs.
		String[] serialPortNames = Serial.list();
		if (serialPortNames.length != 0)
			name = serialPortNames[0];
		else
			name = null;

		rate = Preferences.getInteger("serial.debug_rate");
		parity = Preferences.get("serial.parity").charAt(0);
		databits = Preferences.getInteger("serial.databits");
		stopbits = new Float(Preferences.get("serial.stopbits")).floatValue();
	}

	public void loadXML(Node xml) {
		super.loadXML(xml);

		// load from our XML config, if we have it.
		if (XML.hasChildNode(xml, "portname"))
			name = XML.getChildNodeValue(xml, "portname");
		if (XML.hasChildNode(xml, "rate"))
			rate = Integer.parseInt(XML.getChildNodeValue(xml, "rate"));
		if (XML.hasChildNode(xml, "parity"))
			parity = XML.getChildNodeValue(xml, "parity").charAt(0);
		if (XML.hasChildNode(xml, "databits"))
			databits = Integer.parseInt(XML.getChildNodeValue(xml, "databits"));
		if (databits != 8) {
			throw new java.lang.RuntimeException(
					"Sanguino3G driver requires 8 serial data bits.");
		}
		if (XML.hasChildNode(xml, "stopbits"))
			stopbits = Integer.parseInt(XML.getChildNodeValue(xml, "stopbits"));
	}

	public void initialize() {
		// Create our serial object
		while (serial == null) {
			if (name != null) {
				try {
					Base.logger.log(Level.INFO,"Connecting to " + name + " at "
								+ rate);
					serial = new Serial(name, rate, parity, databits, stopbits);
				} catch (SerialException e) {
					System.out.println("Unable to open port " + name + "\n");
				}
			} else {
				System.out.println("No Serial Port found.\n");
			}
			if ( serial == null ) {
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					// Probably shutting down.
					return;
				}
			}
		}

		// wait till we're initialized
		if (!isInitialized()) {
			// attempt to send version command and retrieve reply.
			try {
				// read our string that means we're started up.
				// after ten seconds, explicitly reset the device.
				waitForStartup(8000);
			} catch (Exception e) {
				// todo: handle init exceptions here
				System.out.println("yarg!");
				e.printStackTrace();
			}
		}

		// did it actually work?
		if (isInitialized()) {
			// okay, take care of version info /etc.
			if (version.compareTo(getMinimumVersion()) < 0) {
				throw new BadFirmwareVersionException(version,getMinimumVersion());
			}
			sendInit();
			super.initialize();

			System.out.println("Ready to print.");

			return;
		} else {
			System.out.println("Unable to connect to firmware.");
		}
	}

	/**
	 * Wait for a startup message. After the specified timeout, replicatorG will
	 * attempt to remotely reset the device.
	 * 
	 * @timeoutMillis the time, in milliseconds, that we should wait for a
	 *                handshake.
	 * @return true if we recieved a handshake; false if we timed out.
	 */
	protected void waitForStartup(int timeoutMillis) {
		assert (serial != null);
		System.err.println("Wait for startup");
		synchronized (serial) {
			serial.setTimeout(timeoutMillis);

			while (!isInitialized()) {
				try {
					version = getVersionInternal();
					if (getVersion() != null)
						setInitialized(true);
				} catch (TimeoutException e) {
					// Timed out waiting; try an explicit reset.
					System.out.println("No connection; trying to pulse RTS to reset device.");
					serial.pulseRTSLow();
					try {
						Thread.sleep(3000); // wait for startup
					} catch (InterruptedException ie) { 
						serial.setTimeout(0);
						return;
					}
					byte[] response = new byte[256];
					StringBuffer respSB = new StringBuffer();
					try {
						while (serial.input.available() > 0) {
							serial.input.read(response);
							respSB.append(response);
						}
						System.err.println("Received "+ respSB.toString());
					} catch (TimeoutException te) {						
					} catch (IOException ioe) {
					}
				}
			}
		}
		// Until we fix the firmware hangs, turn off timeout during
		// builds.
		// TODO: put the timeout back in
		serial.setTimeout(0);
	}

	/**
	 * Sends the command over the serial connection and retrieves a result.
	 */
	protected PacketResponse runCommand(byte[] packet) {
		assert (serial != null);

		if (packet == null || packet.length < 4)
			return null; // skip empty commands or broken commands

		boolean packetSent = false;
		PacketProcessor pp = new PacketProcessor();
		PacketResponse pr = new PacketResponse();

		while (!packetSent) {
			pp = new PacketProcessor();

			synchronized (serial) {
				// make things play nice.
				// try {
				// Thread.sleep(0, 50000);
				// } catch (Exception e) {}

				// do the actual send.
				serial.write(packet);

				if (Base.logger.isLoggable(Level.FINER)) {
					StringBuffer buf = new StringBuffer("OUT: ");
					for (int i = 0; i < packet.length; i++) {
						buf.append(Integer
								.toHexString((int) packet[i] & 0xff));
						buf.append(" ");
					}
					Base.logger.log(Level.FINER,buf.toString());
				}

				try {
					boolean c = false;
					while (!c) {
						int b = serial.input.read();
						if (b == -1) {
							/// Windows has no timeout; busywait
							if (Base.isWindows()) continue;
							throw new TimeoutException(serial);
						}
						c = pp.processByte((byte) b);
					}

					pr = pp.getResponse();

					if (pr.isOK())
						packetSent = true;
					else if (pr.getResponseCode() == PacketResponse.ResponseCode.BUFFER_OVERFLOW) {
						try {
							Thread.sleep(25);
						} catch (Exception e) {
						}
					}
					// TODO: implement other error things.
					else
						break;

				} catch (java.io.IOException ioe) {
					System.out.println(ioe.toString());
				}
			}
		}
		pr.printDebug();
		return pr;
	}

	static boolean isNotifiedFinishedFeature = false;

	public boolean isFinished() {
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.IS_FINISHED.getCode());
		PacketResponse pr = runCommand(pb.getPacket());
		int v = pr.get8();
		if (pr.getResponseCode() == PacketResponse.ResponseCode.UNSUPPORTED) {
			if (!isNotifiedFinishedFeature) {
				System.out.println("IsFinished not supported; update your firmware.");
				isNotifiedFinishedFeature = true;
			}
			return true;
		}
		boolean finished = (v != 0);
		Base.logger.log(Level.FINE,"Is finished: " + Boolean.toString(finished));
		return finished;
	}

	public void dispose() {
		super.dispose();

		if (serial != null)
			serial.dispose();
		serial = null;
	}

	/***************************************************************************
	 * commands used internally to driver
	 **************************************************************************/
	public Version getVersionInternal() {
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.VERSION.getCode());
		pb.add16(Base.VERSION);

		PacketResponse pr = runCommand(pb.getPacket());
		int versionNum = pr.get16();

		Base.logger.log(Level.FINE,"Reported version: "
					+ Integer.toHexString(versionNum));
		if (versionNum == 0) {
			System.err.println("Null version reported!");
			return null;
		}
		Version v = new Version(versionNum / 100, versionNum % 100);
		System.out.println("Version loaded "+v);
		return v;
	}
	
	

	public void sendInit() {
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.INIT.getCode());
		runCommand(pb.getPacket());
	}

	/***************************************************************************
	 * commands for interfacing with the driver directly
	 **************************************************************************/

	public void queuePoint(Point3d p) {
		Base.logger.log(Level.FINE,"Queued point " + p);

		// is this point even step-worthy?
		Point3d deltaSteps = getAbsDeltaSteps(machine.getCurrentPosition(), p);
		double masterSteps = getLongestLength(deltaSteps);

		// okay, we need at least one step.
		if (masterSteps > 0.0) {
			// where we going?
			Point3d steps = machine.mmToSteps(p);

			// how fast are we doing it?
			long micros = convertFeedrateToMicros(machine.getCurrentPosition(),
					p, getSafeFeedrate(deltaSteps));

			// okay, send it off!
			queueAbsolutePoint(steps, micros);

			super.queuePoint(p);
		}
	}

	public Point3d getPosition() {
		return new Point3d();
	}

	/*
	 * //figure out the axis with the most steps. Point3d steps =
	 * getAbsDeltaSteps(getCurrentPosition(), p); Point3d delta_steps =
	 * getDeltaSteps(getCurrentPosition(), p); int max = Math.max((int)steps.x,
	 * (int)steps.y); max = Math.max(max, (int)steps.z);
	 * 
	 * //get the ratio of steps to take each segment double xRatio = steps.x /
	 * max; double yRatio = steps.y / max; double zRatio = steps.z / max;
	 * 
	 * //how many segments will there be? int segmentCount = (int)Math.ceil(max /
	 * 32767.0);
	 * 
	 * //within our range? just do it. if (segmentCount == 1)
	 * queueIncrementalPoint(pb, delta_steps, ticks); else { for (int i=0; i<segmentCount;
	 * i++) { Point3d segmentSteps = new Point3d();
	 * 
	 * //TODO: is this accurate? //TODO: factor in negative deltas! //calculate
	 * our line segments segmentSteps.x = Math.round(32767 * xRatio);
	 * segmentSteps.y = Math.round(32767 * yRatio); segmentSteps.z =
	 * Math.round(32767 * zRatio);
	 * 
	 * //keep track of them. steps.x -= segmentSteps.x; steps.y -=
	 * segmentSteps.y; steps.z -= segmentSteps.z;
	 * 
	 * //send this segment queueIncrementalPoint(pb, segmentSteps, ticks); } }
	 */

	private void queueAbsolutePoint(Point3d steps, long micros) {
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.QUEUE_POINT_ABS.getCode());

		Base.logger.log(Level.FINE,"Queued absolute point " + steps + " at "
					+ micros + " usec.");

		// just add them in now.
		pb.add32((int) steps.x);
		pb.add32((int) steps.y);
		pb.add32((int) steps.z);
		pb.add32((int) micros);

		runCommand(pb.getPacket());
	}

	public void setCurrentPosition(Point3d p) {
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.SET_POSITION.getCode());

		Point3d steps = machine.mmToSteps(p);
		pb.add32((long) steps.x);
		pb.add32((long) steps.y);
		pb.add32((long) steps.z);

		Base.logger.log(Level.FINE,"Set current position to " + p + " (" + steps
					+ ")");

		runCommand(pb.getPacket());

		super.setCurrentPosition(p);
	}

	public void homeAxes(EnumSet<Axis> axes) {
		Base.logger.log(Level.FINE,"Homing axes "+axes.toString());
		byte flags = 0x00;

		// figure out our fastest feedrate.
		Point3d maxFeedrates = machine.getMaximumFeedrates();
		double feedrate = Math.max(maxFeedrates.x, maxFeedrates.y);
		feedrate = Math.max(maxFeedrates.z, feedrate);

		Point3d target = new Point3d();
		
		if (axes.contains(Axis.X)) {
			flags += 1;
			feedrate = Math.min(feedrate, maxFeedrates.x);
			target.x = 1; // just to give us feedrate info.
		}
		if (axes.contains(Axis.Y)) {
			flags += 2;
			feedrate = Math.min(feedrate, maxFeedrates.y);
			target.y = 1; // just to give us feedrate info.
		}
		if (axes.contains(Axis.Z)) {
			flags += 4;
			feedrate = Math.min(feedrate, maxFeedrates.z);
			target.z = 1; // just to give us feedrate info.
		}
		
		// calculate ticks
		long micros = convertFeedrateToMicros(new Point3d(), target, feedrate);
		// send it!
		PacketBuilder pb = new PacketBuilder(
				CommandCodeMaster.FIND_AXES_MINIMUM.getCode());
		pb.add8(flags);
		pb.add32((int) micros);
		pb.add16(300); // default to 5 minutes
		
		runCommand(pb.getPacket());
	}
		

	public void delay(long millis) {
		if (Base.logger.isLoggable(Level.FINER)) {
			Base.logger.log(Level.FINER,"Delaying " + millis + " millis.");
		}

		// send it!
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.DELAY.getCode());
		pb.add32(millis);
		runCommand(pb.getPacket());
	}

	public void openClamp(int clampIndex) {
		// TODO: throw some sort of unsupported exception.
		super.openClamp(clampIndex);
	}

	public void closeClamp(int clampIndex) {
		// TODO: throw some sort of unsupported exception.
		super.closeClamp(clampIndex);
	}

	public void enableDrives() {
		// Command RMB to enable its steppers. Note that they are
		// already automagically enabled by most commands and need
		// not be explicitly enabled.
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.ENABLE_AXES.getCode());
		pb.add8(0x87); // enable x,y,z
		runCommand(pb.getPacket());
		super.enableDrives();
	}

	public void disableDrives() {
		// Command RMB to disable its steppers.
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.ENABLE_AXES.getCode());
		pb.add8(0x07); // disable x,y,z
		runCommand(pb.getPacket());
		super.disableDrives();
	}

	public void changeGearRatio(int ratioIndex) {
		// TODO: throw some sort of unsupported exception.
		super.changeGearRatio(ratioIndex);
	}

	public void requestToolChange(int toolIndex) {
		selectTool(toolIndex);

		Base.logger.log(Level.FINE,"Waiting for tool #" + toolIndex);

		// send it!
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.WAIT_FOR_TOOL.getCode());
		pb.add8((byte) toolIndex);
		pb.add16(100); // delay between master -> slave pings (millis)
		pb.add16(120); // timeout before continuing (seconds)
		runCommand(pb.getPacket());
	}

	public void selectTool(int toolIndex) {
		Base.logger.log(Level.FINE,"Selecting tool #" + toolIndex);

		// send it!
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.CHANGE_TOOL.getCode());
		pb.add8((byte) toolIndex);
		runCommand(pb.getPacket());

		super.selectTool(toolIndex);
	}

	/***************************************************************************
	 * Motor interface functions
	 **************************************************************************/
	public void setMotorRPM(double rpm) {
		// convert RPM into microseconds and then send.
		long microseconds = (int) Math.round(60.0 * 1000000.0 / rpm); // no
		// unsigned
		// ints?!?
		// microseconds = Math.min(microseconds, 2^32-1); // limit to uint32.

		Base.logger.log(Level.FINE,"Setting motor 1 speed to " + rpm + " RPM ("
					+ microseconds + " microseconds)");

		// send it!
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.SET_MOTOR_1_RPM.getCode());
		pb.add8((byte) 4); // length of payload.
		pb.add32(microseconds);
		runCommand(pb.getPacket());

		super.setMotorRPM(rpm);
	}

	public void setMotorSpeedPWM(int pwm) {
		Base.logger.log(Level.FINE,"Setting motor 1 speed to " + pwm + " PWM");

		// send it!
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.SET_MOTOR_1_PWM.getCode());
		pb.add8((byte) 1); // length of payload.
		pb.add8((byte) pwm);
		runCommand(pb.getPacket());

		super.setMotorSpeedPWM(pwm);
	}

	public void enableMotor() {
		// our flag variable starts with motors enabled.
		byte flags = 1;

		// bit 1 determines direction...
		if (machine.currentTool().getMotorDirection() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		Base.logger.log(Level.FINE,"Toggling motor 1 w/ flags: "
					+ Integer.toBinaryString(flags));

		// send it!
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.TOGGLE_MOTOR_1.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8(flags);
		runCommand(pb.getPacket());

		super.enableMotor();
	}

	public void disableMotor() {
		// bit 1 determines direction...
		byte flags = 0;
		if (machine.currentTool().getSpindleDirection() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		Base.logger.log(Level.FINE,"Disabling motor 1");

		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.TOGGLE_MOTOR_1.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8(flags);
		runCommand(pb.getPacket());

		super.disableMotor();
	}

	public int getMotorSpeedPWM() {
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_QUERY.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.GET_MOTOR_1_PWM.getCode());
		PacketResponse pr = runCommand(pb.getPacket());

		// get it
		int pwm = pr.get8();

		Base.logger.log(Level.FINE,"Current motor 1 PWM: " + pwm);

		// set it.
		machine.currentTool().setMotorSpeedReadingPWM(pwm);

		return pwm;
	}

	public double getMotorSpeedRPM() {
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_QUERY.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.GET_MOTOR_1_RPM.getCode());
		PacketResponse pr = runCommand(pb.getPacket());

		// convert back to RPM
		long micros = pr.get32();
		double rpm = (60.0 * 1000000.0 / micros);

		Base.logger.log(Level.FINE,"Current motor 1 RPM: " + rpm + " (" + micros + ")");

		// set it.
		machine.currentTool().setMotorSpeedReadingRPM(rpm);

		return rpm;
	}

	/***************************************************************************
	 * Spindle interface functions
	 **************************************************************************/
	public void setSpindleRPM(double rpm) {
		// convert RPM into microseconds and then send.
		long microseconds = (int) Math.round(60 * 1000000 / rpm); // no
		// unsigned
		// ints?!?
		microseconds = Math.min(microseconds, 2 ^ 32 - 1); // limit to uint32.

		Base.logger.log(Level.FINE,"Setting motor 2 speed to " + rpm + " RPM ("
					+ microseconds + " microseconds)");

		// send it!
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.SET_MOTOR_2_RPM.getCode());
		pb.add8((byte) 4); // payload length
		pb.add32(microseconds);
		runCommand(pb.getPacket());

		super.setSpindleRPM(rpm);
	}

	public void setSpindleSpeedPWM(int pwm) {
		Base.logger.log(Level.FINE,"Setting motor 2 speed to " + pwm + " PWM");

		// send it!
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.SET_MOTOR_2_PWM.getCode());
		pb.add8((byte) 1); // length of payload.
		pb.add8((byte) pwm);
		runCommand(pb.getPacket());

		super.setMotorSpeedPWM(pwm);
	}

	public void enableSpindle() {
		// our flag variable starts with spindles enabled.
		byte flags = 1;

		// bit 1 determines direction...
		if (machine.currentTool().getSpindleDirection() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		Base.logger.log(Level.FINE,"Toggling motor 2 w/ flags: "
					+ Integer.toBinaryString(flags));

		// send it!
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.TOGGLE_MOTOR_2.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8(flags);
		runCommand(pb.getPacket());

		super.enableSpindle();
	}

	public void disableSpindle() {
		// bit 1 determines direction...
		byte flags = 0;
		if (machine.currentTool().getSpindleDirection() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		Base.logger.log(Level.FINE,"Disabling motor 2");

		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.TOGGLE_MOTOR_1.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8(flags);
		runCommand(pb.getPacket());

		super.disableSpindle();
	}

	public double getSpindleSpeedRPM() {
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_QUERY.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.GET_MOTOR_2_RPM.getCode());
		PacketResponse pr = runCommand(pb.getPacket());

		// convert back to RPM
		long micros = pr.get32();
		double rpm = (60.0 * 1000000.0 / micros);

		Base.logger.log(Level.FINE,"Current motor 2 RPM: " + rpm + " (" + micros
					+ ")");

		// set it.
		machine.currentTool().setSpindleSpeedReadingRPM(rpm);

		return rpm;
	}

	public int getSpindleSpeedPWM() {
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_QUERY.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.GET_MOTOR_2_PWM.getCode());
		PacketResponse pr = runCommand(pb.getPacket());

		// get it
		int pwm = pr.get8();

		Base.logger.log(Level.FINE,"Current motor 1 PWM: " + pwm);

		// set it.
		machine.currentTool().setSpindleSpeedReadingPWM(pwm);

		return pwm;
	}

	/***************************************************************************
	 * Temperature interface functions
	 **************************************************************************/
	public void setTemperature(double temperature) {
		// constrain our temperature.
		int temp = (int) Math.round(temperature);
		temp = Math.min(temp, 65535);

		Base.logger.log(Level.FINE,"Setting temperature to " + temp + "C");

		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.SET_TEMP.getCode());
		pb.add8((byte) 2); // payload length
		pb.add16(temp);
		runCommand(pb.getPacket());

		super.setTemperature(temperature);
	}

	public void readTemperature() {
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_QUERY.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.GET_TEMP.getCode());
		PacketResponse pr = runCommand(pb.getPacket());

		int temp = pr.get16();
		machine.currentTool().setCurrentTemperature(temp);

		Base.logger.log(Level.FINE,"Current temperature: "
					+ machine.currentTool().getCurrentTemperature() + "C");

		super.readTemperature();
	}

	/***************************************************************************
	 * Flood Coolant interface functions
	 **************************************************************************/
	public void enableFloodCoolant() {
		// TODO: throw unsupported exception

		super.enableFloodCoolant();
	}

	public void disableFloodCoolant() {
		// TODO: throw unsupported exception

		super.disableFloodCoolant();
	}

	/***************************************************************************
	 * Mist Coolant interface functions
	 **************************************************************************/
	public void enableMistCoolant() {
		// TODO: throw unsupported exception

		super.enableMistCoolant();
	}

	public void disableMistCoolant() {
		// TODO: throw unsupported exception

		super.disableMistCoolant();
	}

	/***************************************************************************
	 * Fan interface functions
	 **************************************************************************/
	public void enableFan() {
		Base.logger.log(Level.FINE,"Enabling fan");

		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.TOGGLE_FAN.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8((byte) 1); // enable
		runCommand(pb.getPacket());

		super.enableFan();
	}

	public void disableFan() {
		Base.logger.log(Level.FINE,"Disabling fan");

		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.TOGGLE_FAN.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8((byte) 0); // disable
		runCommand(pb.getPacket());

		super.disableFan();
	}

	/***************************************************************************
	 * Valve interface functions
	 **************************************************************************/
	public void openValve() {
		Base.logger.log(Level.FINE,"Opening valve");

		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.TOGGLE_VALVE.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8((byte) 1); // enable
		runCommand(pb.getPacket());

		super.openValve();
	}

	public void closeValve() {
		Base.logger.log(Level.FINE,"Closing valve");

		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(CommandCodeSlave.TOGGLE_VALVE.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8((byte) 0); // disable
		runCommand(pb.getPacket());

		super.closeValve();
	}

	/***************************************************************************
	 * Collet interface functions
	 **************************************************************************/
	public void openCollet() {
		// TODO: throw unsupported exception.

		super.openCollet();
	}

	public void closeCollet() {
		// TODO: throw unsupported exception.

		super.closeCollet();
	}

	/***************************************************************************
	 * Pause/unpause functionality for asynchronous devices
	 **************************************************************************/
	public void pause() {
		Base.logger.log(Level.FINE,"Sending asynch pause command");
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.PAUSE.getCode());
		runCommand(pb.getPacket());
	}

	public void unpause() {
		Base.logger.log(Level.FINE,"Sending asynch unpause command");
		// There is no explicit unpause command on the Sanguino3G; instead we
		// use
		// the pause command to toggle the pause state.
		PacketBuilder pb = new PacketBuilder(CommandCodeMaster.PAUSE.getCode());
		runCommand(pb.getPacket());
	}

	/***************************************************************************
	 * Various timer and math functions.
	 **************************************************************************/

	private Point3d getDeltaDistance(Point3d current, Point3d target) {
		// calculate our deltas.
		Point3d delta = new Point3d();
		delta.x = target.x - current.x;
		delta.y = target.y - current.y;
		delta.z = target.z - current.z;

		return delta;
	}

	@SuppressWarnings("unused")
	private Point3d getDeltaSteps(Point3d current, Point3d target) {
		return machine.mmToSteps(getDeltaDistance(current, target));
	}

	private Point3d getAbsDeltaDistance(Point3d current, Point3d target) {
		// calculate our deltas.
		Point3d delta = new Point3d();
		delta.x = Math.abs(target.x - current.x);
		delta.y = Math.abs(target.y - current.y);
		delta.z = Math.abs(target.z - current.z);

		return delta;
	}

	private Point3d getAbsDeltaSteps(Point3d current, Point3d target) {
		return machine.mmToSteps(getAbsDeltaDistance(current, target));
	}

	private long convertFeedrateToMicros(Point3d current, Point3d target,
			double feedrate) {

		Point3d deltaDistance = getAbsDeltaDistance(current, target);
		Point3d deltaSteps = getAbsDeltaSteps(current, target);

		// System.out.println("current: " + current);
		// System.out.println("target: " + target);
		// System.out.println("deltas:" + deltaDistance);

		// try {
		// Thread.sleep(10000);
		// } catch (Exception e) {}

		// how long is our line length?
		double distance = Math.sqrt(deltaDistance.x * deltaDistance.x
				+ deltaDistance.y * deltaDistance.y + deltaDistance.z
				* deltaDistance.z);

		double masterSteps = getLongestLength(deltaSteps);

		// distance is in steps
		// feedrate is in steps/
		// distance / feedrate * 60,000,000 = move duration in microseconds
		double micros = distance / feedrate * 60000000.0;

		// micros / masterSteps = time between steps for master axis.
		double step_delay = micros / masterSteps;

		// System.out.println("Distance: " + distance);
		// System.out.println("Feedrate: " + feedrate);
		// System.out.println("Micros: " + micros);
		// System.out.println("Master steps:" + masterSteps);
		// System.out.println("Step Delay (micros): " + step_delay);

		return (long) Math.round(step_delay);
	}

	private double getLongestLength(Point3d p) {
		// find the dominant axis.
		if (p.x > p.y) {
			if (p.z > p.x)
				return p.z;
			else
				return p.x;
		} else {
			if (p.z > p.y)
				return p.z;
			else
				return p.y;
		}
	}

	@SuppressWarnings("unused")
	private byte convertTicksToPrescaler(long ticks) {
		// these also represent frequency: 1000000 / ticks / 2 = frequency in
		// hz.

		// our slowest speed at our highest resolution ( (2^16-1) * 0.0625 usecs
		// = 4095 usecs (4 millisecond max))
		// range: 8Mhz max - 122hz min
		if (ticks <= 65535L)
			return 1;
		// our slowest speed at our next highest resolution ( (2^16-1) * 0.5
		// usecs = 32767 usecs (32 millisecond max))
		// range:1Mhz max - 15.26hz min
		else if (ticks <= 524280L)
			return 2;
		// our slowest speed at our medium resolution ( (2^16-1) * 4 usecs =
		// 262140 usecs (0.26 seconds max))
		// range: 125Khz max - 1.9hz min
		else if (ticks <= 4194240L)
			return 3;
		// our slowest speed at our medium-low resolution ( (2^16-1) * 16 usecs
		// = 1048560 usecs (1.04 seconds max))
		// range: 31.25Khz max - 0.475hz min
		else if (ticks <= 16776960L)
			return 4;
		// our slowest speed at our lowest resolution ((2^16-1) * 64 usecs =
		// 4194240 usecs (4.19 seconds max))
		// range: 7.812Khz max - 0.119hz min
		else if (ticks <= 67107840L)
			return 5;
		// its really slow... hopefully we can just get by with super slow.
		else
			return 5;
	}

	@SuppressWarnings("unused")
	private int convertTicksToCounter(long ticks) {
		// our slowest speed at our highest resolution ( (2^16-1) * 0.0625 usecs
		// = 4095 usecs)
		if (ticks <= 65535)
			return ((int) (ticks & 0xffff));
		// our slowest speed at our next highest resolution ( (2^16-1) * 0.5
		// usecs = 32767 usecs)
		else if (ticks <= 524280)
			return ((int) ((ticks / 8) & 0xffff));
		// our slowest speed at our medium resolution ( (2^16-1) * 4 usecs =
		// 262140 usecs)
		else if (ticks <= 4194240)
			return ((int) ((ticks / 64) & 0xffff));
		// our slowest speed at our medium-low resolution ( (2^16-1) * 16 usecs
		// = 1048560 usecs)
		else if (ticks <= 16776960)
			return ((int) (ticks / 256));
		// our slowest speed at our lowest resolution ((2^16-1) * 64 usecs =
		// 4194240 usecs)
		else if (ticks <= 67107840)
			return ((int) (ticks / 1024));
		// its really slow... hopefully we can just get by with super slow.
		else
			return 65535;
	}

	public String getDriverName() {
		return "Sanguino3G";
	}

	/***************************************************************************
	 * Stop and system state reset
	 **************************************************************************/
	public void stop() {
		System.out.println("Stop.");
	}

	public void reset() {
		System.out.println("Reset.");
		setInitialized(false);
		initialize();
	}
}