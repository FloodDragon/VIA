package com.stereo.via.rpc.io;

import com.stereo.via.rpc.exc.ServiceException;
import com.stereo.via.rpc.io.factory.SerializerFactory;
import com.stereo.via.rpc.exc.ProtocolException;
import com.stereo.via.rpc.io.deserializer.Deserializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

/**
 * 通用RPC输入流
 * 
 * @author stereo
 */
public class GeneralInput extends AbstractInput {

	private static int END_OF_DATA = -2;

	private static Field _detailMessageField;

	protected SerializerFactory _serializerFactory;

	protected ArrayList _refs;

	private InputStream _is;

	protected int _peek = -1;

	private String _method;

	private Reader _chunkReader;
	private InputStream _chunkInputStream;

	private Throwable _replyFault;

	private StringBuffer _sbuf = new StringBuffer();

	// true if this is the last chunk
	private boolean _isLastChunk;
	// the chunk length
	private int _chunkLength;

	public GeneralInput() {
	}

	public GeneralInput(InputStream is) {
		init(is);
	}

	/**
	 * Sets the serializer factory.
	 */
	public void setSerializerFactory(SerializerFactory factory) {
		_serializerFactory = factory;
	}

	/**
	 * Gets the serializer factory.
	 */
	public SerializerFactory getSerializerFactory() {
		return _serializerFactory;
	}

	/**
	 * Initialize the rpc stream with the underlying input stream.
	 */
	public void init(InputStream is) {
		_is = is;
		_method = null;
		_isLastChunk = true;
		_chunkLength = 0;
		_peek = -1;
		_refs = null;
		_replyFault = null;

		if (_serializerFactory == null)
			_serializerFactory = new SerializerFactory();
	}

	/**
	 * Returns the calls method
	 */
	public String getMethod() {
		return _method;
	}

	/**
	 * Returns any reply fault.
	 */
	public Throwable getReplyFault() {
		return _replyFault;
	}

	/**
	 * Starts reading the call
	 * 
	 * <pre>
	 * c major minor
	 * </pre>
	 */
	public int readCall() throws IOException {
		int tag = read();

		if (tag != 'c')
			throw error("expected rpc call ('c') at " + codeName(tag));

		int major = read();
		int minor = read();

		return (major << 16) + minor;
	}

	/**
	 * For backward compatibility with rpcSkeleton
	 */
	public void skipOptionalCall() throws IOException {
		int tag = read();

		if (tag == 'c') {
			read();
			read();
		} else
			_peek = tag;
	}

	/**
	 * Starts reading the call
	 * 
	 * <p>
	 * A successful completion will have a single value:
	 * 
	 * <pre>
	 * m b16 b8 method
	 * </pre>
	 */
	public String readMethod() throws IOException {
		int tag = read();

		if (tag != 'm')
			throw error("expected rpc method ('m') at " + codeName(tag));
		int d1 = read();
		int d2 = read();

		_isLastChunk = true;
		_chunkLength = d1 * 256 + d2;
		_sbuf.setLength(0);
		int ch;
		while ((ch = parseChar()) >= 0)
			_sbuf.append((char) ch);

		_method = _sbuf.toString();

		return _method;
	}

	/**
	 * Starts reading the call, including the headers.
	 * 
	 * <p>
	 * The call expects the following protocol data
	 * 
	 * <pre>
	 * c major minor
	 * m b16 b8 method
	 * </pre>
	 */
	public void startCall() throws IOException {
		readCall();

		while (readHeader() != null) {
			readObject();
		}

		readMethod();
	}

	/**
	 * Completes reading the call
	 * 
	 * <p>
	 * A successful completion will have a single value:
	 * 
	 * <pre>
	 * z
	 * </pre>
	 */
	public void completeCall() throws IOException {
		int tag = read();

		if (tag == 'z') {
		} else
			throw error("expected end of call ('z') at "
					+ codeName(tag)
					+ ".  Check method arguments and ensure method overloading is enabled if necessary");
	}

	/**
	 * Reads a reply as an object. If the reply has a fault, throws the
	 * exception.
	 */
	public Object readReply(Class expectedClass) throws Throwable {
		int tag = read();

		if (tag != 'r')
			error("expected rpc reply at " + codeName(tag));

		int major = read();
		int minor = read();

		tag = read();
		if (tag == 'f')
			throw prepareFault();
		else {
			_peek = tag;

			Object value = readObject(expectedClass);

			completeValueReply();

			return value;
		}
	}

	/**
	 * Starts reading the reply
	 * 
	 * <p>
	 * A successful completion will have a single value:
	 * 
	 * <pre>
	 * r
	 * </pre>
	 */
	public void startReply() throws Throwable {
		int tag = read();

		if (tag != 'r')
			error("expected rpc reply at " + codeName(tag));

		int major = read();
		int minor = read();

		startReplyBody();
	}

	public void startReplyBody() throws Throwable {
		int tag = read();

		if (tag == 'f')
			throw prepareFault();
		else
			_peek = tag;
	}

	/**
	 * Prepares the fault.
	 */
	private Throwable prepareFault() throws IOException {
		HashMap fault = readFault();

		Object detail = fault.get("detail");
		String message = (String) fault.get("message");

		if (detail instanceof Throwable) {
			_replyFault = (Throwable) detail;

			if (message != null && _detailMessageField != null) {
				try {
					_detailMessageField.set(_replyFault, message);
				} catch (Throwable e) {
				}
			}

			return _replyFault;
		}

		else {
			String code = (String) fault.get("code");

			_replyFault = new ServiceException(message, code, detail);

			return _replyFault;
		}
	}

	/**
	 * Completes reading the call
	 * 
	 * <p>
	 * A successful completion will have a single value:
	 * 
	 * <pre>
	 * z
	 * </pre>
	 */
	public void completeReply() throws IOException {
		int tag = read();

		if (tag != 'z')
			error("expected end of reply at " + codeName(tag));
	}

	/**
	 * Completes reading the call
	 * 
	 * <p>
	 * A successful completion will have a single value:
	 * 
	 * <pre>
	 * z
	 * </pre>
	 */
	public void completeValueReply() throws IOException {
		int tag = read();

		if (tag != 'z')
			error("expected end of reply at " + codeName(tag));
	}

	/**
	 * Reads a header, returning null if there are no headers.
	 * 
	 * <pre>
	 * H b16 b8 value
	 * </pre>
	 */
	public String readHeader() throws IOException {
		int tag = read();

		if (tag == 'H') {
			_isLastChunk = true;
			_chunkLength = (read() << 8) + read();

			_sbuf.setLength(0);
			int ch;
			while ((ch = parseChar()) >= 0)
				_sbuf.append((char) ch);

			return _sbuf.toString();
		}

		_peek = tag;

		return null;
	}

	/**
	 * Reads a null
	 * 
	 * <pre>
	 * N
	 * </pre>
	 */
	public void readNull() throws IOException {
		int tag = read();

		switch (tag) {
		case 'N':
			return;

		default:
			throw expect("null", tag);
		}
	}

	/**
	 * Reads a boolean
	 * 
	 * <pre>
	 * T
	 * F
	 * </pre>
	 */
	public boolean readBoolean() throws IOException {
		int tag = read();

		switch (tag) {
		case 'T':
			return true;
		case 'F':
			return false;
		case 'I':
			return parseInt() == 0;
		case 'L':
			return parseLong() == 0;
		case 'D':
			return parseDouble() == 0.0;
		case 'N':
			return false;

		default:
			throw expect("boolean", tag);
		}
	}

	/**
	 * Reads a byte
	 * 
	 * <pre>
	 * I b32 b24 b16 b8
	 * </pre>
	 */
	/*
	 * public byte readByte() throws IOException { return (byte) readInt(); }
	 */

	/**
	 * Reads a short
	 * 
	 * <pre>
	 * I b32 b24 b16 b8
	 * </pre>
	 */
	public short readShort() throws IOException {
		return (short) readInt();
	}

	/**
	 * Reads an integer
	 * 
	 * <pre>
	 * I b32 b24 b16 b8
	 * </pre>
	 */
	public int readInt() throws IOException {
		int tag = read();

		switch (tag) {
		case 'T':
			return 1;
		case 'F':
			return 0;
		case 'I':
			return parseInt();
		case 'L':
			return (int) parseLong();
		case 'D':
			return (int) parseDouble();

		default:
			throw expect("int", tag);
		}
	}

	/**
	 * Reads a long
	 * 
	 * <pre>
	 * L b64 b56 b48 b40 b32 b24 b16 b8
	 * </pre>
	 */
	public long readLong() throws IOException {
		int tag = read();

		switch (tag) {
		case 'T':
			return 1;
		case 'F':
			return 0;
		case 'I':
			return parseInt();
		case 'L':
			return parseLong();
		case 'D':
			return (long) parseDouble();

		default:
			throw expect("long", tag);
		}
	}

	/**
	 * Reads a float
	 * 
	 * <pre>
	 * D b64 b56 b48 b40 b32 b24 b16 b8
	 * </pre>
	 */
	public float readFloat() throws IOException {
		return (float) readDouble();
	}

	/**
	 * Reads a double
	 * 
	 * <pre>
	 * D b64 b56 b48 b40 b32 b24 b16 b8
	 * </pre>
	 */
	public double readDouble() throws IOException {
		int tag = read();

		switch (tag) {
		case 'T':
			return 1;
		case 'F':
			return 0;
		case 'I':
			return parseInt();
		case 'L':
			return (double) parseLong();
		case 'D':
			return parseDouble();

		default:
			throw expect("long", tag);
		}
	}

	/**
	 * Reads a date.
	 * 
	 * <pre>
	 * T b64 b56 b48 b40 b32 b24 b16 b8
	 * </pre>
	 */
	public long readUTCDate() throws IOException {
		int tag = read();

		if (tag != 'd')
			throw error("expected date at " + codeName(tag));

		long b64 = read();
		long b56 = read();
		long b48 = read();
		long b40 = read();
		long b32 = read();
		long b24 = read();
		long b16 = read();
		long b8 = read();

		return ((b64 << 56) + (b56 << 48) + (b48 << 40) + (b40 << 32)
				+ (b32 << 24) + (b24 << 16) + (b16 << 8) + b8);
	}

	/**
	 * Reads a byte from the stream.
	 */
	public int readChar() throws IOException {
		if (_chunkLength > 0) {
			_chunkLength--;
			if (_chunkLength == 0 && _isLastChunk)
				_chunkLength = END_OF_DATA;

			int ch = parseUTF8Char();
			return ch;
		} else if (_chunkLength == END_OF_DATA) {
			_chunkLength = 0;
			return -1;
		}

		int tag = read();

		switch (tag) {
		case 'N':
			return -1;

		case 'S':
		case 's':
		case 'X':
		case 'x':
			_isLastChunk = tag == 'S' || tag == 'X';
			_chunkLength = (read() << 8) + read();

			_chunkLength--;
			int value = parseUTF8Char();

			// special code so successive read byte won't
			// be read as a single object.
			if (_chunkLength == 0 && _isLastChunk)
				_chunkLength = END_OF_DATA;

			return value;

		default:
			throw new IOException("expected 'S' at " + (char) tag);
		}
	}

	/**
	 * Reads a byte array from the stream.
	 */
	public int readString(char[] buffer, int offset, int length)
			throws IOException {
		int readLength = 0;

		if (_chunkLength == END_OF_DATA) {
			_chunkLength = 0;
			return -1;
		} else if (_chunkLength == 0) {
			int tag = read();

			switch (tag) {
			case 'N':
				return -1;

			case 'S':
			case 's':
			case 'X':
			case 'x':
				_isLastChunk = tag == 'S' || tag == 'X';
				_chunkLength = (read() << 8) + read();
				break;

			default:
				throw new IOException("expected 'S' at " + (char) tag);
			}
		}

		while (length > 0) {
			if (_chunkLength > 0) {
				buffer[offset++] = (char) parseUTF8Char();
				_chunkLength--;
				length--;
				readLength++;
			} else if (_isLastChunk) {
				if (readLength == 0)
					return -1;
				else {
					_chunkLength = END_OF_DATA;
					return readLength;
				}
			} else {
				int tag = read();

				switch (tag) {
				case 'S':
				case 's':
				case 'X':
				case 'x':
					_isLastChunk = tag == 'S' || tag == 'X';
					_chunkLength = (read() << 8) + read();
					break;

				default:
					throw new IOException("expected 'S' at " + (char) tag);
				}
			}
		}

		if (readLength == 0)
			return -1;
		else if (_chunkLength > 0 || !_isLastChunk)
			return readLength;
		else {
			_chunkLength = END_OF_DATA;
			return readLength;
		}
	}

	/**
	 * Reads a string
	 * 
	 * <pre>
	 * S b16 b8 string value
	 * </pre>
	 */
	public String readString() throws IOException {
		int tag = read();

		switch (tag) {
		case 'N':
			return null;

		case 'I':
			return String.valueOf(parseInt());
		case 'L':
			return String.valueOf(parseLong());
		case 'D':
			return String.valueOf(parseDouble());

		case 'S':
		case 's':
		case 'X':
		case 'x':
			_isLastChunk = tag == 'S' || tag == 'X';
			_chunkLength = (read() << 8) + read();

			_sbuf.setLength(0);
			int ch;

			while ((ch = parseChar()) >= 0)
				_sbuf.append((char) ch);

			return _sbuf.toString();

		default:
			throw expect("string", tag);
		}
	}

	/**
	 * Reads an XML node.
	 * 
	 * <pre>
	 * S b16 b8 string value
	 * </pre>
	 */
	public org.w3c.dom.Node readNode() throws IOException {
		int tag = read();

		switch (tag) {
		case 'N':
			return null;

		case 'S':
		case 's':
		case 'X':
		case 'x':
			_isLastChunk = tag == 'S' || tag == 'X';
			_chunkLength = (read() << 8) + read();

			throw error("Can't handle string in this context");

		default:
			throw expect("string", tag);
		}
	}

	/**
	 * Reads a byte array
	 * 
	 * <pre>
	 * B b16 b8 data value
	 * </pre>
	 */
	public byte[] readBytes() throws IOException {
		int tag = read();

		switch (tag) {
		case 'N':
			return null;

		case 'B':
		case 'b':
			_isLastChunk = tag == 'B';
			_chunkLength = (read() << 8) + read();

			ByteArrayOutputStream bos = new ByteArrayOutputStream();

			int data;
			while ((data = parseByte()) >= 0)
				bos.write(data);

			return bos.toByteArray();

		default:
			throw expect("bytes", tag);
		}
	}

	/**
	 * Reads a byte from the stream.
	 */
	public int readByte() throws IOException {
		if (_chunkLength > 0) {
			_chunkLength--;
			if (_chunkLength == 0 && _isLastChunk)
				_chunkLength = END_OF_DATA;

			return read();
		} else if (_chunkLength == END_OF_DATA) {
			_chunkLength = 0;
			return -1;
		}

		int tag = read();

		switch (tag) {
		case 'N':
			return -1;

		case 'B':
		case 'b':
			_isLastChunk = tag == 'B';
			_chunkLength = (read() << 8) + read();

			int value = parseByte();

			// special code so successive read byte won't
			// be read as a single object.
			if (_chunkLength == 0 && _isLastChunk)
				_chunkLength = END_OF_DATA;

			return value;

		default:
			throw new IOException("expected 'B' at " + (char) tag);
		}
	}

	/**
	 * Reads a byte array from the stream.
	 */
	public int readBytes(byte[] buffer, int offset, int length)
			throws IOException {
		int readLength = 0;

		if (_chunkLength == END_OF_DATA) {
			_chunkLength = 0;
			return -1;
		} else if (_chunkLength == 0) {
			int tag = read();

			switch (tag) {
			case 'N':
				return -1;

			case 'B':
			case 'b':
				_isLastChunk = tag == 'B';
				_chunkLength = (read() << 8) + read();
				break;

			default:
				throw new IOException("expected 'B' at " + (char) tag);
			}
		}

		while (length > 0) {
			if (_chunkLength > 0) {
				buffer[offset++] = (byte) read();
				_chunkLength--;
				length--;
				readLength++;
			} else if (_isLastChunk) {
				if (readLength == 0)
					return -1;
				else {
					_chunkLength = END_OF_DATA;
					return readLength;
				}
			} else {
				int tag = read();

				switch (tag) {
				case 'B':
				case 'b':
					_isLastChunk = tag == 'B';
					_chunkLength = (read() << 8) + read();
					break;

				default:
					throw new IOException("expected 'B' at " + (char) tag);
				}
			}
		}

		if (readLength == 0)
			return -1;
		else if (_chunkLength > 0 || !_isLastChunk)
			return readLength;
		else {
			_chunkLength = END_OF_DATA;
			return readLength;
		}
	}

	/**
	 * Reads a fault.
	 */
	private HashMap readFault() throws IOException {
		HashMap map = new HashMap();

		int code = read();
		for (; code > 0 && code != 'z'; code = read()) {
			_peek = code;

			Object key = readObject();
			Object value = readObject();

			if (key != null && value != null)
				map.put(key, value);
		}

		if (code != 'z')
			throw expect("fault", code);

		return map;
	}

	/**
	 * Reads an object from the input stream with an expected type.
	 */
	public Object readObject(Class cl) throws IOException {
		if (cl == null || cl == Object.class)
			return readObject();

		int tag = read();

		switch (tag) {
		case 'N':
			return null;

		case 'M': {
			String type = readType();

			// rpc/3386
			if ("".equals(type)) {
				Deserializer reader;
				reader = _serializerFactory.getDeserializer(cl);

				return reader.readMap(this);
			} else {
				Deserializer reader;
				reader = _serializerFactory.getObjectDeserializer(type);

				return reader.readMap(this);
			}
		}

		case 'V': {
			String type = readType();
			int length = readLength();

			Deserializer reader;
			reader = _serializerFactory.getObjectDeserializer(type);

			if (cl != reader.getType() && cl.isAssignableFrom(reader.getType()))
				return reader.readList(this, length);

			reader = _serializerFactory.getDeserializer(cl);

			Object v = reader.readList(this, length);

			return v;
		}

		case 'R': {
			int ref = parseInt();

			return _refs.get(ref);
		}

		case 'r': {
			String type = readType();
			String url = readString();

			return resolveRemote(type, url);
		}
		}

		_peek = tag;

		// rpc/332i vs rpc/3406
		// return readObject();

		Object value = _serializerFactory.getDeserializer(cl).readObject(this);

		return value;
	}

	/**
	 * Reads an arbitrary object from the input stream when the type is unknown.
	 */
	public Object readObject() throws IOException {
		int tag = read();

		switch (tag) {
		case 'N':
			return null;

		case 'T':
			return Boolean.valueOf(true);

		case 'F':
			return Boolean.valueOf(false);

		case 'I':
			return Integer.valueOf(parseInt());

		case 'L':
			return Long.valueOf(parseLong());

		case 'D':
			return Double.valueOf(parseDouble());

		case 'd':
			return new Date(parseLong());

		case 'x':
		case 'X': {
			_isLastChunk = tag == 'X';
			_chunkLength = (read() << 8) + read();

			return parseXML();
		}

		case 's':
		case 'S': {
			_isLastChunk = tag == 'S';
			_chunkLength = (read() << 8) + read();

			int data;
			_sbuf.setLength(0);

			while ((data = parseChar()) >= 0)
				_sbuf.append((char) data);

			return _sbuf.toString();
		}

		case 'b':
		case 'B': {
			_isLastChunk = tag == 'B';
			_chunkLength = (read() << 8) + read();

			int data;
			ByteArrayOutputStream bos = new ByteArrayOutputStream();

			while ((data = parseByte()) >= 0)
				bos.write(data);

			return bos.toByteArray();
		}

		case 'V': {
			String type = readType();
			int length = readLength();

			return _serializerFactory.readList(this, length, type);
		}

		case 'M': {
			String type = readType();

			return _serializerFactory.readMap(this, type);
		}

		case 'R': {
			int ref = parseInt();

			return _refs.get(ref);
		}

		case 'r': {
			String type = readType();
			String url = readString();

			return resolveRemote(type, url);
		}

		default:
			throw error("unknown code for readObject at " + codeName(tag));
		}
	}

	/**
	 * Reads a remote object.
	 */
	public Object readRemote() throws IOException {
		String type = readType();
		String url = readString();

		return resolveRemote(type, url);
	}

	/**
	 * Reads a reference.
	 */
	public Object readRef() throws IOException {
		return _refs.get(parseInt());
	}

	/**
	 * Reads the start of a list.
	 */
	public int readListStart() throws IOException {
		return read();
	}

	/**
	 * Reads the start of a list.
	 */
	public int readMapStart() throws IOException {
		return read();
	}

	/**
	 * Returns true if this is the end of a list or a map.
	 */
	public boolean isEnd() throws IOException {
		int code = read();

		_peek = code;

		return (code < 0 || code == 'z');
	}

	/**
	 * Reads the end byte.
	 */
	public void readEnd() throws IOException {
		int code = read();

		if (code != 'z')
			throw error("unknown code at " + codeName(code));
	}

	/**
	 * Reads the end byte.
	 */
	public void readMapEnd() throws IOException {
		int code = read();

		if (code != 'z')
			throw error("expected end of map ('z') at " + codeName(code));
	}

	/**
	 * Reads the end byte.
	 */
	public void readListEnd() throws IOException {
		int code = read();

		if (code != 'z')
			throw error("expected end of list ('z') at " + codeName(code));
	}

	/**
	 * Adds a list/map reference.
	 */
	public int addRef(Object ref) {
		if (_refs == null)
			_refs = new ArrayList();

		_refs.add(ref);

		return _refs.size() - 1;
	}

	/**
	 * Adds a list/map reference.
	 */
	public void setRef(int i, Object ref) {
		_refs.set(i, ref);
	}

	/**
	 * Resets the references for streaming.
	 */
	public void resetReferences() {
		if (_refs != null)
			_refs.clear();
	}

	/**
	 * Resolves a remote object.
	 */
	public Object resolveRemote(String type, String url) throws IOException {
		RemoteResolver resolver = getRemoteResolver();

		if (resolver != null)
			return resolver.lookup(type, url);
		else
			return new Remote(type, url);
	}

	/**
	 * Parses a type from the stream.
	 * 
	 * <pre>
	 * t b16 b8
	 * </pre>
	 */
	public String readType() throws IOException {
		int code = read();

		if (code != 't') {
			_peek = code;
			return "";
		}

		_isLastChunk = true;
		_chunkLength = (read() << 8) + read();

		_sbuf.setLength(0);
		int ch;
		while ((ch = parseChar()) >= 0)
			_sbuf.append((char) ch);

		return _sbuf.toString();
	}

	/**
	 * Parses the length for an array
	 * 
	 * <pre>
	 * l b32 b24 b16 b8
	 * </pre>
	 */
	public int readLength() throws IOException {
		int code = read();

		if (code != 'l') {
			_peek = code;
			return -1;
		}

		return parseInt();
	}

	/**
	 * Parses a 32-bit integer value from the stream.
	 * 
	 * <pre>
	 * b32 b24 b16 b8
	 * </pre>
	 */
	private int parseInt() throws IOException {
		int b32 = read();
		int b24 = read();
		int b16 = read();
		int b8 = read();

		return (b32 << 24) + (b24 << 16) + (b16 << 8) + b8;
	}

	/**
	 * Parses a 64-bit long value from the stream.
	 * 
	 * <pre>
	 * b64 b56 b48 b40 b32 b24 b16 b8
	 * </pre>
	 */
	private long parseLong() throws IOException {
		long b64 = read();
		long b56 = read();
		long b48 = read();
		long b40 = read();
		long b32 = read();
		long b24 = read();
		long b16 = read();
		long b8 = read();

		return ((b64 << 56) + (b56 << 48) + (b48 << 40) + (b40 << 32)
				+ (b32 << 24) + (b24 << 16) + (b16 << 8) + b8);
	}

	/**
	 * Parses a 64-bit double value from the stream.
	 * 
	 * <pre>
	 * b64 b56 b48 b40 b32 b24 b16 b8
	 * </pre>
	 */
	private double parseDouble() throws IOException {
		long b64 = read();
		long b56 = read();
		long b48 = read();
		long b40 = read();
		long b32 = read();
		long b24 = read();
		long b16 = read();
		long b8 = read();

		long bits = ((b64 << 56) + (b56 << 48) + (b48 << 40) + (b40 << 32)
				+ (b32 << 24) + (b24 << 16) + (b16 << 8) + b8);

		return Double.longBitsToDouble(bits);
	}

	org.w3c.dom.Node parseXML() throws IOException {
		throw new UnsupportedOperationException();
	}

	/**
	 * Reads a character from the underlying stream.
	 */
	private int parseChar() throws IOException {
		while (_chunkLength <= 0) {
			if (_isLastChunk)
				return -1;

			int code = read();

			switch (code) {
			case 's':
			case 'x':
				_isLastChunk = false;

				_chunkLength = (read() << 8) + read();
				break;

			case 'S':
			case 'X':
				_isLastChunk = true;

				_chunkLength = (read() << 8) + read();
				break;

			default:
				throw expect("string", code);
			}

		}

		_chunkLength--;

		return parseUTF8Char();
	}

	/**
	 * Parses a single UTF8 character.
	 */
	private int parseUTF8Char() throws IOException {
		int ch = read();

		if (ch < 0x80)
			return ch;
		else if ((ch & 0xe0) == 0xc0) {
			int ch1 = read();
			int v = ((ch & 0x1f) << 6) + (ch1 & 0x3f);

			return v;
		} else if ((ch & 0xf0) == 0xe0) {
			int ch1 = read();
			int ch2 = read();
			int v = ((ch & 0x0f) << 12) + ((ch1 & 0x3f) << 6) + (ch2 & 0x3f);

			return v;
		} else
			throw error("bad utf-8 encoding at " + codeName(ch));
	}

	/**
	 * Reads a byte from the underlying stream.
	 */
	private int parseByte() throws IOException {
		while (_chunkLength <= 0) {
			if (_isLastChunk) {
				return -1;
			}

			int code = read();

			switch (code) {
			case 'b':
				_isLastChunk = false;

				_chunkLength = (read() << 8) + read();
				break;

			case 'B':
				_isLastChunk = true;

				_chunkLength = (read() << 8) + read();
				break;

			default:
				throw expect("byte[]", code);
			}
		}

		_chunkLength--;

		return read();
	}

	/**
	 * Reads bytes based on an input stream.
	 */
	public InputStream readInputStream() throws IOException {
		int tag = read();

		switch (tag) {
		case 'N':
			return null;

		case 'B':
		case 'b':
			_isLastChunk = tag == 'B';
			_chunkLength = (read() << 8) + read();
			break;

		default:
			throw expect("inputStream", tag);
		}

		return new InputStream() {
			boolean _isClosed = false;

			public int read() throws IOException {
				if (_isClosed || _is == null)
					return -1;

				int ch = parseByte();
				if (ch < 0)
					_isClosed = true;

				return ch;
			}

			public int read(byte[] buffer, int offset, int length)
					throws IOException {
				if (_isClosed || _is == null)
					return -1;

				int len = GeneralInput.this.read(buffer, offset, length);
				if (len < 0)
					_isClosed = true;

				return len;
			}

			public void close() throws IOException {
				while (read() >= 0) {
				}

				_isClosed = true;
			}
		};
	}

	/**
	 * Reads bytes from the underlying stream.
	 */
	int read(byte[] buffer, int offset, int length) throws IOException {
		int readLength = 0;

		while (length > 0) {
			while (_chunkLength <= 0) {
				if (_isLastChunk)
					return readLength == 0 ? -1 : readLength;

				int code = read();

				switch (code) {
				case 'b':
					_isLastChunk = false;

					_chunkLength = (read() << 8) + read();
					break;

				case 'B':
					_isLastChunk = true;

					_chunkLength = (read() << 8) + read();
					break;

				default:
					throw expect("byte[]", code);
				}
			}

			int sublen = _chunkLength;
			if (length < sublen)
				sublen = length;

			sublen = _is.read(buffer, offset, sublen);
			offset += sublen;
			readLength += sublen;
			length -= sublen;
			_chunkLength -= sublen;
		}

		return readLength;
	}

	final int read() throws IOException {
		if (_peek >= 0) {
			int value = _peek;
			_peek = -1;
			return value;
		}

		int ch = _is.read();

		return ch;
	}

	public void close() {
		_is = null;
	}

	public Reader getReader() {
		return null;
	}

	protected IOException expect(String expect, int ch) {
		return error("expected " + expect + " at " + codeName(ch));
	}

	protected String codeName(int ch) {
		if (ch < 0)
			return "end of file";
		else
			return "0x" + Integer.toHexString(ch & 0xff) + " (" + (char) +ch
					+ ")";
	}

	protected IOException error(String message) {
		if (_method != null)
			return new ProtocolException(_method + ": " + message);
		else
			return new ProtocolException(message);
	}

	static {
		try {
			_detailMessageField = Throwable.class
					.getDeclaredField("detailMessage");
			_detailMessageField.setAccessible(true);
		} catch (Throwable e) {
		}
	}
}
