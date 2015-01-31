package org.luaj.vm2.lib.jse;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.LibFunction;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * Subclass of {@link LibFunction} which implements the features of the luajava package.
 * <p>
 * Luajava is an approach to mixing lua and java using simple functions that bind
 * java classes and methods to lua dynamically.  The API is documented on the
 * <a href="http://www.keplerproject.org/luajava/">luajava</a> documentation pages.
 * <p>
 * Typically, this library is included as part of a call to either
 * {@link JsePlatform#standardGlobals()}
 * <p>
 * To instantiate and use it directly,
 * link it into your globals table via {@link LuaValue#load(LuaValue)} using code such as:
 * <pre> {@code
 * LuaTable _G = new LuaTable();
 * LuaThread.setGlobals(_G);
 * LuaC.install();
 * _G.load(new BaseLib());
 * _G.load(new PackageLib());
 * _G.load(new LuajavaLib());
 * _G.get("loadstring").call( LuaValue.valueOf(
 * 		"sys = luajava.bindClass('java.lang.System')\n"+
 * 		"print ( sys:currentTimeMillis() )\n" ) ).call();
 * } </pre>
 * This example is not intended to be realistic - only to show how the {@link LuajavaLib}
 * may be initialized by hand.  In practice, the {@code luajava} library is available
 * on all JSE platforms via the call to {@link JsePlatform#standardGlobals()}
 * and the luajava api's are simply invoked from lua.
 * <p>
 * This has been implemented to match as closely as possible the behavior in the corresponding library in C.
 * @see LibFunction
 * @see org.luaj.vm2.lib.jse.JsePlatform
 * @see org.luaj.vm2.lib.jme.JmePlatform
 * @see LuaC
 * @see <a href="http://www.keplerproject.org/luajava/manual.html#luareference">http://www.keplerproject.org/luajava/manual.html#luareference</a>
 */
public class LuajavaLib extends VarArgFunction
{
	static final int      INIT                     = 0;
	static final int      BINDCLASS                = 1;
	static final int      NEWINSTANCE              = 2;
	static final int      NEW                      = 3;
	static final int      CREATEPROXY              = 4;
	static final int      LOADLIB                  = 5;

	static final String[] NAMES                    = {
	                                               "bindClass",
	                                               "newInstance",
	                                               "new",
	                                               "createProxy",
	                                               "loadLib",
	                                               };

	static final int      METHOD_MODIFIERS_VARARGS = 0x80;

	public LuajavaLib()
	{
	}

	@Override
	public Varargs invoke(Varargs args)
	{
		try
		{
			switch(_opcode)
			{
				case INIT:
				{
					LuaTable t = new LuaTable();
					bind(t, LuajavaLib.class, NAMES, BINDCLASS);
					env.set("luajava", t);
					PackageLib.instance.LOADED.set("luajava", t);
					return t;
				}
				case BINDCLASS:
				{
					final Class<?> clazz = classForName(args.checkjstring(1));
					return JavaClass.forClass(clazz);
				}
				case NEWINSTANCE:
				case NEW:
				{
					// get constructor
					final LuaValue c = args.checkvalue(1);
					final Class<?> clazz = (_opcode == NEWINSTANCE ? classForName(c.tojstring()) : (Class<?>)c.checkuserdata(Class.class));
					final Varargs consargs = args.subargs(2);
					return JavaClass.forClass(clazz).getConstructor().invoke(consargs);
				}

				case CREATEPROXY:
				{
					final int niface = args.narg() - 1;
					if(niface <= 0)
					    throw new LuaError("no interfaces");
					final LuaValue lobj = args.checktable(niface + 1);

					// get the interfaces
					final Class<?>[] ifaces = new Class<?>[niface];
					for(int i = 0; i < niface; i++)
						ifaces[i] = classForName(args.checkjstring(i + 1));

					// create the invocation handler
					InvocationHandler handler = new InvocationHandler()
					{
						@SuppressWarnings("null")
						@Override
						public Object invoke(Object proxy, Method method, Object[] args2) throws Throwable
						{
							String name = method.getName();
							LuaValue func = lobj.get(name);
							if(func.isnil())
							    return null;
							boolean isvarargs = ((method.getModifiers() & METHOD_MODIFIERS_VARARGS) != 0);
							int n = args2 != null ? args2.length : 0;
							LuaValue[] v;
							if(isvarargs)
							{
								Object o = args2[--n];
								int m = Array.getLength(o);
								v = new LuaValue[n + m];
								for(int i = 0; i < n; i++)
									v[i] = CoerceJavaToLua.coerce(args2[i]);
								for(int i = 0; i < m; i++)
									v[i + n] = CoerceJavaToLua.coerce(Array.get(o, i));
							}
							else
							{
								v = new LuaValue[n];
								for(int i = 0; i < n; i++)
									v[i] = CoerceJavaToLua.coerce(args2[i]);
							}
							LuaValue result = func.invoke(v).arg1();
							return CoerceLuaToJava.coerce(result, method.getReturnType());
						}
					};

					// create the proxy object
					Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), ifaces, handler);

					// return the proxy
					return LuaValue.userdataOf(proxy);
				}
				case LOADLIB:
				{
					// get constructor
					String classname = args.checkjstring(1);
					String methodname = args.checkjstring(2);
					Class<?> clazz = classForName(classname);
					Method method = clazz.getMethod(methodname, new Class[] {});
					Object result = method.invoke(clazz, new Object[] {});
					if(result instanceof LuaValue)
					    return (LuaValue)result;
					return NIL;
				}
				default:
					throw new LuaError("not yet supported: " + this);
			}
		}
		catch(LuaError e)
		{
			throw e;
		}
		catch(InvocationTargetException ite)
		{
			throw new LuaError(ite.getTargetException());
		}
		catch(Exception e)
		{
			throw new LuaError(e);
		}
	}

	// load classes using app loader to allow luaj to be used as an extension
	protected static Class<?> classForName(String name) throws ClassNotFoundException
	{
		return Class.forName(name, true, ClassLoader.getSystemClassLoader());
	}
}
