package org.luaj.vm2;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
* Class to manage loading of {@link Prototype} instances.
* <p>
* The {@link LoadState} class exposes one main function,
* namely {@link #load(InputStream, String, LuaValue)},
* to be used to load code from a particular input stream.
* <p>
* A simple pattern for loading and executing code is
* <pre> {@code
* LuaValue _G = JsePlatform.standardGlobals();
* LoadState.load( new FileInputStream("main.lua"), "main.lua", _G ).call();
* } </pre>
* This should work regardless of which {@link LuaCompiler}
* has been installed.
* <p>
*
* Prior to loading code, a compiler should be installed.
* By default, the LuaC compiler is installed.
* <p>
* To override the default compiler with, say, the {@link LuaJC}
* lua-to-java bytecode compiler, install it before loading,
* for example:
* <pre> {@code
* LuaValue _G = JsePlatform.standardGlobals();
* LuaJC.install();
* LoadState.load( new FileInputStream("main.lua"), "main.lua", _G ).call();
* } </pre>
*
* @see LuaCompiler
* @see LuaClosure
* @see LuaFunction
*/
public final class LoadState
{
	/** format corresponding to non-number-patched lua, all numbers are floats or doubles */
	public static final int          NUMBER_FORMAT_FLOATS_OR_DOUBLES = 0;

	/** format corresponding to non-number-patched lua, all numbers are ints */
	public static final int          NUMBER_FORMAT_INTS_ONLY         = 1;

	/** format corresponding to number-patched lua, all numbers are 32-bit (4 byte) ints */
	public static final int          NUMBER_FORMAT_NUM_PATCH_INT32   = 4;

	// type constants
	public static final int          LUA_TINT                        = -2;
	public static final int          LUA_TNONE                       = -1;
	public static final int          LUA_TNIL                        = 0;
	public static final int          LUA_TBOOLEAN                    = 1;
	public static final int          LUA_TLIGHTUSERDATA              = 2;
	public static final int          LUA_TNUMBER                     = 3;
	public static final int          LUA_TSTRING                     = 4;
	public static final int          LUA_TTABLE                      = 5;
	public static final int          LUA_TFUNCTION                   = 6;
	public static final int          LUA_TUSERDATA                   = 7;
	public static final int          LUA_TTHREAD                     = 8;
	public static final int          LUA_TVALUE                      = 9;

	private static final LuaValue[]  NOVALUES                        = {};
	private static final Prototype[] NOPROTOS                        = {};
	private static final LocVars[]   NOLOCVARS                       = {};
	private static final LuaString[] NOSTRVALUES                     = {};
	private static final int[]       NOINTS                          = {};

	/** Signature byte indicating the file is a compiled binary chunk */
	private static final byte[]      LUA_SIGNATURE                   = { '\033', 'L', 'u', 'a' };

	/** Name for compiled chunks */
	public static final String       SOURCE_BINARY_STRING            = "binary string";

	/** for header of binary files -- this is Lua 5.1 */
	public static final int          LUAC_VERSION                    = 0x51;

	/** for header of binary files -- this is the official format */
	public static final int          LUAC_FORMAT                     = 0;

	/** size of header of binary files */
	public static final int          LUAC_HEADERSIZE                 = 12;

	/** input stream from which we are loading */
	private final DataInputStream    _is;

	/** Read buffer */
	private byte[]                   _buf                            = new byte[512];

	private int                      _luacSizeofSizeT;
	private int                      _luacNumberFormat;
	private boolean                  _luacLittleEndian;

	/** Load a 4-byte int value from the input stream
	 * @return the int value laoded.
	 **/
	private int loadInt() throws IOException
	{
		_is.readFully(_buf, 0, 4);
		return _luacLittleEndian ?
		        (_buf[3] << 24) | ((0xff & _buf[2]) << 16) | ((0xff & _buf[1]) << 8) | (0xff & _buf[0]) :
		        (_buf[0] << 24) | ((0xff & _buf[1]) << 16) | ((0xff & _buf[2]) << 8) | (0xff & _buf[3]);
	}

	/** Load an array of int values from the input stream
	 * @return the array of int values laoded.
	 **/
	private int[] loadIntArray() throws IOException
	{
		int n = loadInt();
		if(n == 0)
		    return NOINTS;

		// read all data at once
		int m = n << 2;
		if(_buf.length < m)
		    _buf = new byte[m];
		_is.readFully(_buf, 0, m);
		int[] array = new int[n];
		for(int i = 0, j = 0; i < n; ++i, j += 4)
			array[i] = _luacLittleEndian ?
			        (_buf[j + 3] << 24) | ((0xff & _buf[j + 2]) << 16) | ((0xff & _buf[j + 1]) << 8) | (0xff & _buf[j + 0]) :
			        (_buf[j + 0] << 24) | ((0xff & _buf[j + 1]) << 16) | ((0xff & _buf[j + 2]) << 8) | (0xff & _buf[j + 3]);

		return array;
	}

	/** Load a long  value from the input stream
	 * @return the long value laoded.
	 **/
	private long loadInt64() throws IOException
	{
		int a, b;
		if(_luacLittleEndian)
		{
			a = loadInt();
			b = loadInt();
		}
		else
		{
			b = loadInt();
			a = loadInt();
		}
		return (((long)b) << 32) | (a & 0xffffffffL);
	}

	/** Load a lua strin gvalue from the input stream
	 * @return the {@link LuaString} value laoded.
	 **/
	private LuaString loadString() throws IOException
	{
		int size = _luacSizeofSizeT == 8 ? (int)loadInt64() : loadInt();
		if(size == 0)
		    return null;
		byte[] bytes = new byte[size];
		_is.readFully(bytes, 0, size);
		return LuaString.valueOf(bytes, 0, bytes.length - 1);
	}

	/**
	 * Convert bits in a long value to a {@link LuaValue}.
	 * @param bits long value containing the bits
	 * @return {@link LuaInteger} or {@link LuaDouble} whose value corresponds to the bits provided.
	 */
	public static LuaValue longBitsToLuaNumber(long bits)
	{
		if((bits & ((1L << 63) - 1)) == 0L)
		{
			return LuaValue.ZERO;
		}

		int e = (int)((bits >> 52) & 0x7ffL) - 1023;

		if(e >= 0 && e < 31)
		{
			long f = bits & 0xFFFFFFFFFFFFFL;
			int shift = 52 - e;
			long intPrecMask = (1L << shift) - 1;
			if((f & intPrecMask) == 0)
			{
				int intValue = (int)(f >> shift) | (1 << e);
				return LuaInteger.valueOf(((bits >> 63) != 0) ? -intValue : intValue);
			}
		}

		return LuaValue.valueOf(Double.longBitsToDouble(bits));
	}

	/**
	 * Load a number from a binary chunk
	 * @return the {@link LuaValue} loaded
	 * @throws IOException if an i/o exception occurs
	 */
	private LuaValue loadNumber() throws IOException
	{
		if(_luacNumberFormat == NUMBER_FORMAT_INTS_ONLY)
		    return LuaInteger.valueOf(loadInt());
		return longBitsToLuaNumber(loadInt64());
	}

	/**
	 * Load a list of constants from a binary chunk
	 * @param f the function prototype
	 * @throws IOException if an i/o exception occurs
	 */
	private void loadConstants(Prototype f) throws IOException
	{
		int n = loadInt();
		LuaValue[] values = n > 0 ? new LuaValue[n] : NOVALUES;
		for(int i = 0; i < n; i++)
		{
			switch(_is.readByte())
			{
				case LUA_TNIL:
					values[i] = LuaValue.NIL;
					break;
				case LUA_TBOOLEAN:
					values[i] = (0 != _is.readUnsignedByte() ? LuaValue.TRUE : LuaValue.FALSE);
					break;
				case LUA_TINT:
					values[i] = LuaInteger.valueOf(loadInt());
					break;
				case LUA_TNUMBER:
					values[i] = loadNumber();
					break;
				case LUA_TSTRING:
					values[i] = loadString();
					break;
				default:
					throw new IllegalStateException("bad constant");
			}
		}
		f.k = values;

		n = loadInt();
		Prototype[] protos = n > 0 ? new Prototype[n] : NOPROTOS;
		for(int i = 0; i < n; i++)
			protos[i] = loadFunction(f.source);
		f.p = protos;
	}

	/**
	 * Load the debug infor for a function prototype
	 * @param f the function Prototype
	 * @throws IOException if there is an i/o exception
	 */
	private void loadDebug(Prototype f) throws IOException
	{
		f.lineinfo = loadIntArray();
		int n = loadInt();
		f.locvars = n > 0 ? new LocVars[n] : NOLOCVARS;
		for(int i = 0; i < n; i++)
		{
			LuaString varname = loadString();
			int startpc = loadInt();
			int endpc = loadInt();
			f.locvars[i] = new LocVars(varname, startpc, endpc);
		}

		n = loadInt();
		f.upvalues = n > 0 ? new LuaString[n] : NOSTRVALUES;
		for(int i = 0; i < n; i++)
		{
			f.upvalues[i] = loadString();
		}
	}

	/**
	 * Load a function prototype from the input stream
	 * @param p name of the source
	 * @return {@link Prototype} instance that was loaded
	 * @throws IOException
	 */
	public Prototype loadFunction(LuaString p) throws IOException
	{
		Prototype f = new Prototype();
//		L.push(f);
		f.source = loadString();
		if(f.source == null)
		    f.source = p;
		f.linedefined = loadInt();
		f.lastlinedefined = loadInt();
		f.nups = _is.readUnsignedByte();
		f.numparams = _is.readUnsignedByte();
		f.is_vararg = _is.readUnsignedByte();
		f.maxstacksize = _is.readUnsignedByte();
		f.code = loadIntArray();
		loadConstants(f);
		loadDebug(f);

		// TODO: add check here, for debugging purposes, I believe
		// see ldebug.c
//		IF (!luaG_checkcode(f), "bad code");

//		L.pop();
		return f;
	}

	/**
	 * Load the lua chunk header values.
	 * @throws IOException if an i/o exception occurs.
	 */
	public void loadHeader() throws IOException
	{
		_is.readByte();
		_is.readByte();
		_luacLittleEndian = (_is.readByte() != 0);
		_is.readByte();
		_luacSizeofSizeT = _is.readByte();
		_is.readByte();
		_is.readByte();
		_luacNumberFormat = _is.readByte();
	}

	/**
	 * Load lua thought to be a binary chunk from its first byte from an input stream.
	 * @param firstByte the first byte of the input stream
	 * @param stream InputStream to read, after having read the first byte already
	 * @param name Name to apply to the loaded chunk
	 * @return {@link Prototype} that was loaded
	 * @throws IllegalArgumentException if the signature is bac
	 * @throws IOException if an IOException occurs
	 */
	public static Prototype loadBinaryChunk(int firstByte, InputStream stream, String name) throws IOException
	{
		// check rest of signature
		if(firstByte != LUA_SIGNATURE[0]
		        || stream.read() != LUA_SIGNATURE[1]
		        || stream.read() != LUA_SIGNATURE[2]
		        || stream.read() != LUA_SIGNATURE[3])
		    throw new IllegalArgumentException("bad signature");

		// load file as a compiled chunk
		String sname = getSourceName(name);
		LoadState s = new LoadState(stream);
		s.loadHeader();

		// check format
		switch(s._luacNumberFormat)
		{
			case NUMBER_FORMAT_FLOATS_OR_DOUBLES:
			case NUMBER_FORMAT_INTS_ONLY:
			case NUMBER_FORMAT_NUM_PATCH_INT32:
				break;
			default:
				throw new LuaError("unsupported int size");
		}
		return s.loadFunction(LuaString.valueOf(sname));
	}

	/**
	 * Construct a source name from a supplied chunk name
	 * @param name String name that appears in the chunk
	 * @return source file name
	 */
	public static String getSourceName(String name)
	{
		String sname = name;
		if(name.startsWith("@") || name.startsWith("="))
			sname = name.substring(1);
		else if(name.startsWith("\033"))
		    sname = SOURCE_BINARY_STRING;
		return sname;
	}

	/** Private constructor for create a load state */
	private LoadState(InputStream stream)
	{
		_is = new DataInputStream(stream);
	}
}
