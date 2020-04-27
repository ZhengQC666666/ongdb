/*
 * Copyright (c) 2002-2018 "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * Copyright (c) 2018-2020 "Graph Foundation"
 * Graph Foundation, Inc. [https://graphfoundation.org]
 *
 * This file is part of ONgDB Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) as found
 * in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 */
package org.neo4j.cluster.protocol.atomicbroadcast.multipaxos;

import java.io.Serializable;

import org.neo4j.cluster.com.message.Message;

/**
 * Id of a particular Paxos instance.
 */
public class InstanceId
        implements Serializable, Comparable<InstanceId>
{
    private static final long serialVersionUID = 2505002855546341672L;

    public static final String INSTANCE = "instance";

    long id;

    public InstanceId( Message message )
    {
        this( message.getHeader( INSTANCE ) );
    }

    public InstanceId( String string )
    {
        this( Long.parseLong( string ) );
    }

    public InstanceId( long id )
    {
        this.id = id;
    }

    public long getId()
    {
        return id;
    }

    @Override
    public int compareTo( InstanceId o )
    {
        return Long.compare( id, o.getId() );
    }

    @Override
    public boolean equals( Object o )
    {
        if ( this == o )
        {
            return true;
        }
        if ( o == null || getClass() != o.getClass() )
        {
            return false;
        }

        InstanceId that = (InstanceId) o;

        return id == that.id;
    }

    @Override
    public int hashCode()
    {
        return (int) (id ^ (id >>> 32));
    }

    @Override
    public String toString()
    {
        return Long.toString( id );
    }
}
