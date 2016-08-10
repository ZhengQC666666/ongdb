/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.core.consensus;

import org.junit.Test;

import java.io.IOException;

import org.neo4j.coreedge.core.consensus.log.InMemoryRaftLog;
import org.neo4j.coreedge.core.consensus.log.RaftLog;
import org.neo4j.coreedge.core.consensus.log.RaftLogCursor;
import org.neo4j.coreedge.core.consensus.log.RaftLogEntry;
import org.neo4j.coreedge.core.consensus.membership.RaftTestGroup;
import org.neo4j.coreedge.messaging.Inbound;
import org.neo4j.coreedge.identity.MemberId;
import org.neo4j.coreedge.identity.RaftTestMemberSetBuilder;
import org.neo4j.coreedge.core.consensus.schedule.ControlledRenewableTimeoutService;
import org.neo4j.kernel.impl.core.DatabasePanicEventGenerator;
import org.neo4j.kernel.internal.DatabaseHealth;
import org.neo4j.kernel.internal.KernelEventHandlers;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.logging.NullLog;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.neo4j.coreedge.core.consensus.RaftMachine.Timeouts.ELECTION;
import static org.neo4j.coreedge.core.consensus.TestMessageBuilders.appendEntriesRequest;
import static org.neo4j.coreedge.core.consensus.TestMessageBuilders.voteRequest;
import static org.neo4j.coreedge.core.consensus.TestMessageBuilders.voteResponse;
import static org.neo4j.coreedge.core.consensus.log.RaftLogHelper.readLogEntry;
import static org.neo4j.coreedge.core.consensus.roles.Role.FOLLOWER;
import static org.neo4j.coreedge.identity.RaftTestMember.member;
import static org.neo4j.helpers.collection.Iterables.last;
import static org.neo4j.helpers.collection.Iterators.asSet;

public class RaftMachineTest
{
    private MemberId myself = member( 0 );

    /* A few members that we use at will in tests. */
    private MemberId member1 = member( 1 );
    private MemberId member2 = member( 2 );
    private MemberId member3 = member( 3 );
    private MemberId member4 = member( 4 );

    private ReplicatedInteger data1 = ReplicatedInteger.valueOf( 1 );

    private RaftLog raftLog = new InMemoryRaftLog();

    @Test
    public void shouldAlwaysStartAsFollower() throws Exception
    {
        // when
        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .build();

        // then
        assertEquals( FOLLOWER, raft.currentRole() );
    }

    @Test
    public void shouldRequestVotesOnElectionTimeout() throws Exception
    {
        // Given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();
        OutboundMessageCollector messages = new OutboundMessageCollector();

        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts )
                .outbound( messages )
                .build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) );

        // When
        timeouts.invokeTimeout( ELECTION );

        // Then
        assertThat( messages.sentTo( myself ).size(), equalTo( 0 ) );

        assertThat( messages.sentTo( member1 ).size(), equalTo( 1 ) );
        assertThat( messages.sentTo( member1 ).get( 0 ), instanceOf( RaftMessages.Vote.Request.class ) );

        assertThat( messages.sentTo( member2 ).size(), equalTo( 1 ) );
        assertThat( messages.sentTo( member2 ).get( 0 ), instanceOf( RaftMessages.Vote.Request.class ) );
    }

    @Test
    public void shouldBecomeLeaderInMajorityOf3() throws Exception
    {
        // Given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();
        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts ).build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) ); // @logIndex=0

        timeouts.invokeTimeout( ELECTION );
        assertThat( raft.isLeader(), is( false ) );

        // When
        raft.handle( voteResponse().from( member1 ).term( 1 ).grant().build() );

        // Then
        assertThat( raft.isLeader(), is( true ) );
    }

    @Test
    public void shouldBecomeLeaderInMajorityOf5() throws Exception
    {
        // Given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();
        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts ).build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2, member3, member4 ) ) );
        // @logIndex=0

        timeouts.invokeTimeout( ELECTION );

        raft.handle( voteResponse().from( member1 ).term( 1 ).grant().build() );
        assertThat( raft.isLeader(), is( false ) );

        // When
        raft.handle( voteResponse().from( member2 ).term( 1 ).grant().build() );

        // Then
        assertThat( raft.isLeader(), is( true ) );
    }

    @Test
    public void shouldNotBecomeLeaderOnMultipleVotesFromSameMember() throws Exception
    {
        // Given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();
        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts ).build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2, member3, member4 ) ) );
        // @logIndex=0

        timeouts.invokeTimeout( ELECTION );

        // When
        raft.handle( voteResponse().from( member1 ).term( 1 ).grant().build() );
        raft.handle( voteResponse().from( member1 ).term( 1 ).grant().build() );

        // Then
        assertThat( raft.isLeader(), is( false ) );
    }

    @Test
    public void shouldNotBecomeLeaderWhenVotingOnItself() throws Exception
    {
        // Given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();
        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts ).build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) ); // @logIndex=0

        timeouts.invokeTimeout( ELECTION );

        // When
        raft.handle( voteResponse().from( myself ).term( 1 ).grant().build() );

        // Then
        assertThat( raft.isLeader(), is( false ) );
    }

    @Test
    public void shouldNotBecomeLeaderWhenMembersVoteNo() throws Exception
    {
        // Given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();
        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts ).build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) ); // @logIndex=0

        timeouts.invokeTimeout( ELECTION );

        // When
        raft.handle( voteResponse().from( member1 ).term( 1 ).deny().build() );
        raft.handle( voteResponse().from( member2 ).term( 1 ).deny().build() );

        // Then
        assertThat( raft.isLeader(), is( false ) );
    }

    @Test
    public void shouldNotBecomeLeaderByVotesFromOldTerm() throws Exception
    {
        // Given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();
        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts ).build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) ); // @logIndex=0

        timeouts.invokeTimeout( ELECTION );
        // When
        raft.handle( voteResponse().from( member1 ).term( 0 ).grant().build() );
        raft.handle( voteResponse().from( member2 ).term( 0 ).grant().build() );

        // Then
        assertThat( raft.isLeader(), is( false ) );
    }

    @Test
    public void shouldVoteFalseForCandidateInOldTerm() throws Exception
    {
        // Given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();
        OutboundMessageCollector messages = new OutboundMessageCollector();

        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts )
                .outbound( messages )
                .build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) ); // @logIndex=0

        // When
        raft.handle( voteRequest().from( member1 ).term( -1 ).candidate( member1 ).lastLogIndex( 0 ).lastLogTerm( -1
        ).build() );

        // Then
        assertThat( messages.sentTo( member1 ).size(), equalTo( 1 ) );
        assertThat( messages.sentTo( member1 ), hasItem( voteResponse().from( myself ).term( 0 ).deny().build() ) );
    }

    @Test
    public void shouldNotBecomeLeaderByVotesFromFutureTerm() throws Exception
    {
        // Given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();
        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts ).build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) ); // @logIndex=0

        timeouts.invokeTimeout( ELECTION );

        // When
        raft.handle( voteResponse().from( member1 ).term( 2 ).grant().build() );
        raft.handle( voteResponse().from( member2 ).term( 2 ).grant().build() );

        assertThat( raft.isLeader(), is( false ) );
        assertEquals( raft.term(), 2L );
    }

    @Test
    public void shouldAppendNewLeaderBarrierAfterBecomingLeader() throws Exception
    {
        // Given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();
        OutboundMessageCollector messages = new OutboundMessageCollector();

        InMemoryRaftLog raftLog = new InMemoryRaftLog();
        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts )
                .outbound( messages )
                .raftLog( raftLog )
                .build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) );

        // When
        timeouts.invokeTimeout( ELECTION );
        raft.handle( voteResponse().from( member1 ).term( 1 ).grant().build() );

        // Then
        assertEquals( new NewLeaderBarrier(), readLogEntry( raftLog, raftLog.appendIndex() ).content() );
    }

    @Test
    public void leaderShouldSendHeartBeatsOnHeartbeatTimeout() throws Exception
    {
        // Given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();
        OutboundMessageCollector messages = new OutboundMessageCollector();

        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts )
                .outbound( messages )
                .build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) );

        timeouts.invokeTimeout( ELECTION );
        raft.handle( voteResponse().from( member1 ).term( 1 ).grant().build() );

        // When
        timeouts.invokeTimeout( RaftMachine.Timeouts.HEARTBEAT );

        // Then
        assertTrue( last( messages.sentTo( member1 ) ) instanceof RaftMessages.Heartbeat );
        assertTrue( last( messages.sentTo( member2 ) ) instanceof RaftMessages.Heartbeat );
    }

    @Test
    public void shouldThrowExceptionIfReceivesClientRequestWithNoLeaderElected() throws Exception
    {
        // Given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();

        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts ).build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) ); // @logIndex=0

        try
        {
            // When
            // There is no leader
            raft.getLeader();
            fail( "Should have thrown exception" );
        }
        // Then
        catch ( NoLeaderFoundException e )
        {
            // expected
        }
    }

    @Test
    public void shouldPersistAtSpecifiedLogIndex() throws Exception
    {
        // given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();
        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts )
                .raftLog( raftLog )
                .build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) ); // @logIndex=0

        // when
        raft.handle(
                appendEntriesRequest().from( member1 ).leaderTerm( 0 ).prevLogIndex( 0 )
                        .prevLogTerm( 0 ).logEntry( new RaftLogEntry( 0, data1 ) ).leaderCommit( -1 ).build());
        // then
        assertEquals( 1, raftLog.appendIndex() );
        assertEquals( data1, readLogEntry( raftLog, 1 ).content() );
    }

    @Test
    public void newMembersShouldBeIncludedInHeartbeatMessages() throws Exception
    {
        // Given
        DirectNetworking network = new DirectNetworking();
        final MemberId newMember = member( 99 );
        DirectNetworking.Inbound newMemberInbound = network.new Inbound( newMember );
        final OutboundMessageCollector messages = new OutboundMessageCollector();
        newMemberInbound.registerHandler( new Inbound.MessageHandler<RaftMessages.RaftMessage>()
        {
            @Override
            public void handle( RaftMessages.RaftMessage message )
            {
                messages.send( newMember, message );
            }
        } );

        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();

        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts )
                .outbound( messages )
                .build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) );

        // We make ourselves the leader
        timeouts.invokeTimeout( ELECTION );
        raft.handle( voteResponse().from( member1 ).term( 1 ).grant().build() );

        // When
        raft.setTargetMembershipSet( asSet( myself, member1, member2, newMember ) );
        network.processMessages();

        timeouts.invokeTimeout( RaftMachine.Timeouts.HEARTBEAT );
        network.processMessages();

        // Then
        assertEquals( RaftMessages.AppendEntries.Request.class, messages.sentTo( newMember ).get( 0 ).getClass() );
    }

    @Test
    public void shouldThrowBootstrapExceptionIfUnableToBootstrap() throws Throwable
    {
        // given
        ExplodingRaftLog explodingLog = new ExplodingRaftLog();
        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .raftLog( explodingLog )
                .build();
        explodingLog.startExploding();
        try
        {
            // when
            raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) );
            fail( "Contract expects exception so that others can take remedial action" );
        }
        catch ( RaftMachine.BootstrapException e )
        {
            // expected
        }
    }

    @Test
    public void shouldMonitorLeaderNotFound() throws Exception
    {
        // Given
        ControlledRenewableTimeoutService timeouts = new ControlledRenewableTimeoutService();

        Monitors monitors = new Monitors();
        LeaderNotFoundMonitor leaderNotFoundMonitor = new StubLeaderNotFoundMonitor();
        monitors.addMonitorListener( leaderNotFoundMonitor );

        RaftMachine raft = new RaftMachineBuilder( myself, 3, RaftTestMemberSetBuilder.INSTANCE )
                .timeoutService( timeouts )
                .monitors(monitors)
                .build();

        raft.bootstrapWithInitialMembers( new RaftTestGroup( asSet( myself, member1, member2 ) ) ); // @logIndex=0

        try
        {
            // When
            // There is no leader
            raft.getLeader();
            fail( "Should have thrown exception" );
        }
        // Then
        catch ( NoLeaderFoundException e )
        {
            // expected
            assertEquals(1, leaderNotFoundMonitor.leaderNotFoundExceptions());
        }
    }

    private static class ExplodingRaftLog implements RaftLog
    {
        private boolean startExploding = false;

        @Override
        public long append( RaftLogEntry... entries ) throws IOException
        {
            if ( startExploding )
            {
                throw new IOException( "Boom! append" );
            }
            else
            {
                return 0;
            }
        }

        @Override
        public void truncate( long fromIndex ) throws IOException
        {
            throw new IOException( "Boom! truncate" );
        }

        @Override
        public long prune( long safeIndex )
        {
            return -1;
        }

        @Override
        public long appendIndex()
        {
            return -1;
        }

        @Override
        public long prevIndex()
        {
            return -1;
        }

        @Override
        public long readEntryTerm( long logIndex ) throws IOException
        {
            return -1;
        }

        @Override
        public RaftLogCursor getEntryCursor( long fromIndex ) throws IOException
        {
            if ( startExploding )
            {
                throw new IOException( "Boom! entry cursor" );
            }
            else
            {
                return RaftLogCursor.empty();
            }
        }

        @Override
        public long skip( long index, long term )
        {
            return -1;
        }

        public void startExploding()
        {
            startExploding = true;
        }
    }

    private static class TestDatabaseHealth extends DatabaseHealth
    {

        private boolean hasPanicked = false;

        public TestDatabaseHealth()
        {
            super( new DatabasePanicEventGenerator( new KernelEventHandlers( NullLog.getInstance() ) ),
                    NullLog.getInstance() );
        }

        @Override
        public void panic( Throwable cause )
        {
            this.hasPanicked = true;
        }

        public boolean hasPanicked()
        {
            return hasPanicked;
        }
    }

    private class StubLeaderNotFoundMonitor implements LeaderNotFoundMonitor
    {
        long count = 0;

        @Override
        public long leaderNotFoundExceptions()
        {
            return count;
        }

        @Override
        public void increment()
        {
            count++;
        }
    }
}
