/*
 * Copyright (c) 2018-2020 "Graph Foundation"
 * Graph Foundation, Inc. [https://graphfoundation.org]
 *
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of ONgDB.
 *
 * ONgDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.codegen;

import java.util.HashMap;
import java.util.Map;

class CodeLoader extends ClassLoader
{
    private final Map<String/*class name*/,ByteCodes> bytecodes = new HashMap<>();

    CodeLoader( ClassLoader parent )
    {
        super( parent );
    }

    synchronized void addSources( Iterable<? extends ByteCodes> sources, ByteCodeVisitor visitor )
    {
        for ( ByteCodes source : sources )
        {
            visitor.visitByteCode( source.name(), source.bytes().duplicate() );
            bytecodes.put( source.name(), source );
        }
    }

    @Override
    protected synchronized Class<?> findClass( String name ) throws ClassNotFoundException
    {
        ByteCodes codes = bytecodes.remove( name );
        if ( codes == null )
        {
            throw new ClassNotFoundException( name );
        }
        String packageName = name.substring( 0, name.lastIndexOf( '.' ) );
        if ( getPackage( packageName ) == null )
        {
            definePackage( packageName, "", "", "", "", "", "", null );
        }
        return defineClass( name, codes.bytes(), null );
    }
}
