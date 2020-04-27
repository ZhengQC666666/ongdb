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
package org.neo4j.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.helpers.collection.Iterables;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class TestImpermanentGraphDatabase
{
    private GraphDatabaseService db;

    @Before
    public void createDb()
    {
        db = new TestGraphDatabaseFactory().newImpermanentDatabase();
    }

    @After
    public void tearDown()
    {
        db.shutdown();
    }

    @Test
    public void should_keep_data_between_start_and_shutdown()
    {
        createNode();

        assertEquals( "Expected one new node", 1, nodeCount() );
    }

    @Test
    public void data_should_not_survive_shutdown()
    {
        createNode();
        db.shutdown();

        createDb();

        assertEquals( "Should not see anything.", 0, nodeCount() );
    }

    @Test
    public void should_remove_all_data()
    {
        try ( Transaction tx = db.beginTx() )
        {
            RelationshipType relationshipType = RelationshipType.withName( "R" );

            Node n1 = db.createNode();
            Node n2 = db.createNode();
            Node n3 = db.createNode();

            n1.createRelationshipTo(n2, relationshipType);
            n2.createRelationshipTo(n1, relationshipType);
            n3.createRelationshipTo(n1, relationshipType);

            tx.success();
        }

        cleanDatabaseContent( db );

        assertThat( nodeCount(), is( 0L ) );
    }

    private void cleanDatabaseContent( GraphDatabaseService db )
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.getAllRelationships().forEach( Relationship::delete );
            db.getAllNodes().forEach( Node::delete );
            tx.success();
        }
    }

    private long nodeCount()
    {
        Transaction transaction = db.beginTx();
        long count = Iterables.count( db.getAllNodes() );
        transaction.close();
        return count;
    }

    private void createNode()
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.createNode();
            tx.success();
        }
    }
}
