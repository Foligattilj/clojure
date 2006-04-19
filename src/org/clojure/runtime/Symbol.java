/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Common Public License 1.0 (http://opensource.org/licenses/cpl.php)
 *   which can be found in the file CPL.TXT at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Mar 25, 2006 11:42:47 AM */

package org.clojure.runtime;

import java.util.HashMap;

public class Symbol extends AMap{

final public static HashMap table = new HashMap();

public final String name;

public String toString()
	{
	return name;
	}

public static Symbol intern(String name)
	{
	synchronized(table)
		{
		Symbol sym = (Symbol) table.get(name);
		if(sym == null)
			table.put(name, sym = new Symbol(name));
		return sym;
		}
	}

/**
 * Used by intern()
 * @param name
 */
Symbol(String name)
	{
	this.name = name;
	}




}
