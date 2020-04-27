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
package org.neo4j.kernel.ha;

import org.neo4j.internal.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.kernel.ha.transaction.TransactionPropagator;
import org.neo4j.kernel.impl.api.TransactionCommitProcess;
import org.neo4j.kernel.impl.api.TransactionToApply;
import org.neo4j.kernel.impl.transaction.state.IntegrityValidator;
import org.neo4j.kernel.impl.transaction.tracing.CommitEvent;
import org.neo4j.storageengine.api.TransactionApplicationMode;

/**
 * Commit process on the master side in HA, where transactions either comes in from slaves committing,
 * or gets created and committed directly on the master.
 */
public class MasterTransactionCommitProcess implements TransactionCommitProcess
{

    private final TransactionCommitProcess inner;
    private final TransactionPropagator txPropagator;
    private final IntegrityValidator validator;
    private final Monitor monitor;

    public interface Monitor
    {
        void missedReplicas( int number );
    }

    public MasterTransactionCommitProcess( TransactionCommitProcess commitProcess, TransactionPropagator txPropagator,
            IntegrityValidator validator, Monitor monitor )
    {
        this.inner = commitProcess;
        this.txPropagator = txPropagator;
        this.validator = validator;
        this.monitor = monitor;
    }

    @Override
    public long commit( TransactionToApply batch, CommitEvent commitEvent, TransactionApplicationMode mode )
            throws TransactionFailureException
    {
        validate( batch );
        long result = inner.commit( batch, commitEvent, mode );

        // Assuming all the transactions come from the same author
        int missedReplicas = txPropagator.committed( result, batch.transactionRepresentation().getAuthorId() );

        if ( missedReplicas > 0 )
        {
            monitor.missedReplicas( missedReplicas );
        }

        return result;
    }

    private void validate( TransactionToApply batch ) throws TransactionFailureException
    {
        while ( batch != null )
        {
            validator.validateTransactionStartKnowledge(
                    batch.transactionRepresentation().getLatestCommittedTxWhenStarted() );
            batch = batch.next();
        }
    }

}
