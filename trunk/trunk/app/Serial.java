/* -*- mode: jde; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  PSerial - class for serial port goodness
  Part of the Processing project - http://processing.org

  Copyright (c) 2004 Ben Fry & Casey Reas

  This library is free software; you can redistribute it and/or
  modify it under the terms of the GNU Lesser General Public
  License as published by the Free Software Foundation; either
  version 2.1 of the License, or (at your option) any later version.

  This library is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
  Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General
  Public License along with this library; if not, write to the
  Free Software Foundation, Inc., 59 Temple Place, Suite 330,
  Boston, MA  02111-1307  USA
*/

package processing.app;
//import processing.core.*;

import gnu.io.*;

import java.io.*;
import java.util.*;


public class Serial implements SerialPortEventListener {

  //PApplet parent;

  // properties can be passed in for default values
  // otherwise defaults to 9600 N81

  // these could be made static, which might be a solution
  // for the classloading problem.. because if code ran again,
  // the static class would have an object that could be closed

  SerialPort port;

  int rate;
  int parity;
  int databits;
  int stopbits;
  boolean monitor = false;

  // read buffer and streams 

  InputStream input;
  OutputStream output;

  byte buffer[] = new byte[32768];
  int bufferIndex;
  int bufferLast;

  public Serial(boolean monitor) throws SerialException {
    this(Preferences.get("serial.port"),
      Preferences.getInteger("serial.debug_rate"),
      Preferences.get("serial.parity").charAt(0),
      Preferences.getInteger("serial.databits"),
      new Float(Preferences.get("serial.stopbits")).floatValue());
    this.monitor = monitor;
  }
    
  public Serial() throws SerialException {
    this(Preferences.get("serial.port"),
      Preferences.getInteger("serial.debug_rate"),
      Preferences.get("serial.parity").charAt(0),
      Preferences.getInteger("serial.databits"), 
      new Float(Preferences.get("serial.stopbits")).floatValue());
  }

  public Serial(int irate) throws SerialException {
    this(Preferences.get("serial.port"), irate,
      Preferences.get("serial.parity").charAt(0),
      Preferences.getInteger("serial.databits"), 
      new Float(Preferences.get("serial.stopbits")).floatValue());
  }

  public Serial(String iname, int irate) throws SerialException {
    this(iname, irate, Preferences.get("serial.parity").charAt(0),
    Preferences.getInteger("serial.databits"), 
    new Float(Preferences.get("serial.stopbits")).floatValue());
  }

  public Serial(String iname) throws SerialException {
    this(iname, Preferences.getInteger("serial.debug_rate"),
      Preferences.get("serial.parity").charAt(0),
      Preferences.getInteger("serial.databits"),
      new Float(Preferences.get("serial.stopbits")).floatValue());
  }

  public Serial(String iname, int irate,
                 char iparity, int idatabits, float istopbits)
  throws SerialException {
    //if (port != null) port.close();
    //this.parent = parent;
    //parent.attach(this);

    this.rate = irate;

    parity = SerialPort.PARITY_NONE;
    if (iparity == 'E') parity = SerialPort.PARITY_EVEN;
    if (iparity == 'O') parity = SerialPort.PARITY_ODD;

    this.databits = idatabits;

    stopbits = SerialPort.STOPBITS_1;
    if (istopbits == 1.5f) stopbits = SerialPort.STOPBITS_1_5;
    if (istopbits == 2) stopbits = SerialPort.STOPBITS_2;

    try {
      port = null;
      Enumeration portList = CommPortIdentifier.getPortIdentifiers();
      while (portList.hasMoreElements()) {
        CommPortIdentifier portId =
          (CommPortIdentifier) portList.nextElement();

        if (portId.getPortType() == CommPortIdentifier.PORT_SERIAL) {
          //System.out.println("found " + portId.getName());
          if (portId.getName().equals(iname)) {
            System.out.println("looking for "+iname);
            port = (SerialPort)portId.open("serial madness", 2000);
            input = port.getInputStream();
            output = port.getOutputStream();
            port.setSerialPortParams(rate, databits, stopbits, parity);
            port.addEventListener(this);
            port.notifyOnDataAvailable(true);
            System.out.println("opening, ready to roll");
          }
        }
      }
    } catch (PortInUseException e) {
      throw new SerialException("Serial port '" + iname + "' already in use.  Try quiting any programs that may be using it.");
    } catch (Exception e) {
      throw new SerialException("Error opening serial port '" + iname + "'.", e);
//      //errorMessage("<init>", e);
//      //exception = e;
//      //e.printStackTrace();
    }
    
    if (port == null) {
      throw new SerialException("Serial port '" + iname + "' not found.  Did you select the right one from the Tools > Serial Port menu?");
    }
  }


  public void setup() {
    //parent.registerCall(this, DISPOSE);
  }


  //public void size(int w, int h) { }

  //public void pre() { }

  //public void draw() { }

  //public void post() { }

  //public void mouse(java.awt.event.MouseEvent event) { }

  //public void key(java.awt.event.KeyEvent e) { }


  public void dispose() {
    try {
      // do io streams need to be closed first?
      if (input != null) input.close();
      if (output != null) output.close();

    } catch (Exception e) {
      e.printStackTrace();
    }
    input = null;
    output = null;

    try {
      if (port != null) port.close();  // close the port

    } catch (Exception e) {
      e.printStackTrace();
    }
    port = null;
  }


  synchronized public void serialEvent(SerialPortEvent serialEvent) {
    //System.out.println("serial port event"); // " + serialEvent);
    //System.out.flush();
    //System.out.println("into");
    //System.out.flush();
    //System.err.println("type " + serialEvent.getEventType());
    //System.err.println("ahoooyey");
    //System.err.println("ahoooyeysdfsdfsdf");
    if (serialEvent.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
      //System.out.println("data available");
      //System.err.flush();
      try {
        while (input.available() > 0) {
        //if (input.available() > 0) {
          //serial = input.read();
          //serialEvent();
          //buffer[bufferCount++] = (byte) serial;
          synchronized (buffer) {
            if (bufferLast == buffer.length) {
              byte temp[] = new byte[bufferLast << 1];
              System.arraycopy(buffer, 0, temp, 0, bufferLast);
              buffer = temp;
            }
            buffer[bufferLast++] = (byte) input.read();
            if(monitor == true)
              System.out.print((char) input.read());
            
            //System.err.println(input.available() + " " + ((char) buffer[bufferLast-1]));
          }
        }
        //System.out.println("no more");

      } catch (IOException e) {
        errorMessage("serialEvent", e);
        e.printStackTrace();
      }
      catch (Exception e) {
        e.printStackTrace();
      }
    }
    //System.out.println("out of");
    //System.err.println("out of event " + serialEvent.getEventType());
  }


  /**
   * Returns the number of bytes that have been read from serial
   * and are waiting to be dealt with by the user.
   */
  public int available() {
    return (bufferLast - bufferIndex);
  }


  /**
   * Ignore all the bytes read so far and empty the buffer.
   */
  public void clear() {
    bufferLast = 0;
    bufferIndex = 0;
  }


  /**
   * Returns a number between 0 and 255 for the next byte that's 
   * waiting in the buffer. 
   * Returns -1 if there was no byte (although the user should
   * first check available() to see if things are ready to avoid this)
   */
  public int read() {
    if (bufferIndex == bufferLast) return -1;

    synchronized (buffer) {
      int outgoing = buffer[bufferIndex++] & 0xff;
      if (bufferIndex == bufferLast) {  // rewind
        bufferIndex = 0;
        bufferLast = 0;
      }
      return outgoing;
    }
  }


  /**
   * Returns the next byte in the buffer as a char.
   * Returns -1, or 0xffff, if nothing is there.
   */
  public char readChar() {
    if (bufferIndex == bufferLast) return (char)(-1);
    return (char) read();
  }


  /**
   * Return a byte array of anything that's in the serial buffer.
   * Not particularly memory/speed efficient, because it creates
   * a byte array on each read, but it's easier to use than 
   * readBytes(byte b[]) (see below).
   */
  public byte[] readBytes() {
    if (bufferIndex == bufferLast) return null;

    synchronized (buffer) {
      int length = bufferLast - bufferIndex;
      byte outgoing[] = new byte[length];
      System.arraycopy(buffer, bufferIndex, outgoing, 0, length);

      bufferIndex = 0;  // rewind
      bufferLast = 0;
      return outgoing;
    }
  }


  /**
   * Grab whatever is in the serial buffer, and stuff it into a 
   * byte buffer passed in by the user. This is more memory/time 
   * efficient than readBytes() returning a byte[] array. 
   *
   * Returns an int for how many bytes were read. If more bytes
   * are available than can fit into the byte array, only those
   * that will fit are read.
   */
  public int readBytes(byte outgoing[]) {
    if (bufferIndex == bufferLast) return 0;

    synchronized (buffer) {
      int length = bufferLast - bufferIndex;
      if (length > outgoing.length) length = outgoing.length;
      System.arraycopy(buffer, bufferIndex, outgoing, 0, length);

      bufferIndex += length;
      if (bufferIndex == bufferLast) {
        bufferIndex = 0;  // rewind
        bufferLast = 0;
      }
      return length;
    }    
  }


  /**
   * Reads from the serial port into a buffer of bytes up to and
   * including a particular character. If the character isn't in 
   * the serial buffer, then 'null' is returned.
   */
  public byte[] readBytesUntil(int interesting) {
    if (bufferIndex == bufferLast) return null;
    byte what = (byte)interesting;

    synchronized (buffer) {
      int found = -1; 
      for (int k = bufferIndex; k < bufferLast; k++) {
        if (buffer[k] == what) {
          found = k;
          break;
        }
      }
      if (found == -1) return null;

      int length = found - bufferIndex + 1;
      byte outgoing[] = new byte[length];
      System.arraycopy(buffer, bufferIndex, outgoing, 0, length);

      bufferIndex = 0;  // rewind
      bufferLast = 0;
      return outgoing;
    }
  }


  /**
   * Reads from the serial port into a buffer of bytes until a 
   * particular character. If the character isn't in the serial
   * buffer, then 'null' is returned.
   *
   * If outgoing[] is not big enough, then -1 is returned, 
   *   and an error message is printed on the console.
   * If nothing is in the buffer, zero is returned.
   * If 'interesting' byte is not in the buffer, then 0 is returned.
   */
  public int readBytesUntil(int interesting, byte outgoing[]) {
    if (bufferIndex == bufferLast) return 0;
    byte what = (byte)interesting;

    synchronized (buffer) {
      int found = -1; 
      for (int k = bufferIndex; k < bufferLast; k++) {
        if (buffer[k] == what) {
          found = k;
          break;
        }
      }
      if (found == -1) return 0;

      int length = found - bufferIndex + 1;
      if (length > outgoing.length) {
        System.err.println("readBytesUntil() byte buffer is" +
                           " too small for the " + length + 
                           " bytes up to and including char " + interesting);
        return -1;
      }
      //byte outgoing[] = new byte[length];
      System.arraycopy(buffer, bufferIndex, outgoing, 0, length);

      bufferIndex += length;
      if (bufferIndex == bufferLast) {
        bufferIndex = 0;  // rewind
        bufferLast = 0;
      }
      return length;
    }
  }


  /**
   * Return whatever has been read from the serial port so far
   * as a String. It assumes that the incoming characters are ASCII. 
   *
   * If you want to move Unicode data, you can first convert the
   * String to a byte stream in the representation of your choice
   * (i.e. UTF8 or two-byte Unicode data), and send it as a byte array.
   */
  public String readString() {
    if (bufferIndex == bufferLast) return null;
    return new String(readBytes());
  }


  /**
   * Combination of readBytesUntil and readString. See caveats in 
   * each function. Returns null if it still hasn't found what
   * you're looking for.
   *
   * If you want to move Unicode data, you can first convert the
   * String to a byte stream in the representation of your choice
   * (i.e. UTF8 or two-byte Unicode data), and send it as a byte array.
   */
  public String readStringUntil(int interesting) {
    byte b[] = readBytesUntil(interesting);
    if (b == null) return null;
    return new String(b);
  }


  /**
   * This will handle both ints, bytes and chars transparently.
   */
  public void write(int what) {  // will also cover char
    try {
      output.write(what & 0xff);  // for good measure do the &
      output.flush();   // hmm, not sure if a good idea

    } catch (Exception e) { // null pointer or serial port dead
      errorMessage("write", e);
    }
  }


  public void write(byte bytes[]) {
    try {
      output.write(bytes);
      output.flush();   // hmm, not sure if a good idea

    } catch (Exception e) { // null pointer or serial port dead
      //errorMessage("write", e);
      e.printStackTrace();
    }
  }


  /**
   * Write a String to the output. Note that this doesn't account
   * for Unicode (two bytes per char), nor will it send UTF8 
   * characters.. It assumes that you mean to send a byte buffer 
   * (most often the case for networking and serial i/o) and
   * will only use the bottom 8 bits of each char in the string.
   * (Meaning that internally it uses String.getBytes)
   *
   * If you want to move Unicode data, you can first convert the
   * String to a byte stream in the representation of your choice
   * (i.e. UTF8 or two-byte Unicode data), and send it as a byte array.
   */
  public void write(String what) {
    write(what.getBytes());
  }

  public void setDTR(boolean state) {
    port.setDTR(state);
  }

  /**
   * If this just hangs and never completes on Windows, 
   * it may be because the DLL doesn't have its exec bit set.
   * Why the hell that'd be the case, who knows.
   */
  static public String[] list() {
    Vector list = new Vector();
    try {
      //System.err.println("trying");
      Enumeration portList = CommPortIdentifier.getPortIdentifiers();
      //System.err.println("got port list");
      while (portList.hasMoreElements()) {
        CommPortIdentifier portId = 
          (CommPortIdentifier) portList.nextElement();
        //System.out.println(portId);

        if (portId.getPortType() == CommPortIdentifier.PORT_SERIAL) {
          String name = portId.getName();
          list.addElement(name);
        }
      }

    } catch (UnsatisfiedLinkError e) {
      //System.err.println("1");
      errorMessage("ports", e);

    } catch (Exception e) {
      //System.err.println("2");
      errorMessage("ports", e);
    }
    //System.err.println("move out");
    String outgoing[] = new String[list.size()];
    list.copyInto(outgoing);
    return outgoing;
  }


  /**
   * General error reporting, all corraled here just in case
   * I think of something slightly more intelligent to do.
   */
  static public void errorMessage(String where, Throwable e) {
    System.err.println("Error inside Serial." + where + "()");
    e.printStackTrace();
  }
}