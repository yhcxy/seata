/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.server.coordinator;

import java.util.Collection;
import java.util.stream.Stream;

import io.seata.core.exception.TransactionException;
import io.seata.core.model.BranchStatus;
import io.seata.core.model.BranchType;
import io.seata.core.model.GlobalStatus;
import io.seata.core.rpc.RemotingServer;
import io.seata.server.session.BranchSession;
import io.seata.server.session.GlobalSession;
import io.seata.server.session.SessionHelper;
import io.seata.server.session.SessionHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The type Default core test.
 *
 * @author zhimo.xiao @gmail.com
 */
@SpringBootTest
public class DefaultCoreTest {

    private static DefaultCore core;
    private static RemotingServer remotingServer;

    private static final String applicationId = "demo-child-app";

    private static final String txServiceGroup = "default_tx_group";

    private static final String txName = "tx-1";

    private static final int timeout = 3000;

    private static final String resourceId = "tb_1";

    private static final String clientId = "c_1";

    private static final String lockKeys_1 = "tb_11:11";

    private static final String lockKeys_2 = "tb_12:12";

    private static final String applicationData = "{\"data\":\"test\"}";

    private GlobalSession globalSession;

    /**
     * Init session manager.
     *
     * @throws Exception the exception
     */
    @BeforeAll
    public static void initSessionManager(ApplicationContext context) throws Exception {
        SessionHolder.init(null);
        remotingServer = new DefaultCoordinatorTest.MockServerMessageSender();
        core = new DefaultCore(remotingServer);
    }

    /**
     * Destroy session manager.
     */
    @AfterAll
    public static void destroySessionManager() {
        SessionHolder.destroy();
    }

    /**
     * Clean.
     *
     * @throws TransactionException the transaction exception
     */
    @AfterEach
    public synchronized void clean() throws TransactionException, InterruptedException {
        if (globalSession != null) {
            int n = 10;
            while (n-- > 0) {
                try {
                    globalSession.end();
                    return;
                } catch (TransactionException e) {
                    throw e;
                } catch (Exception e) {
                    e.printStackTrace();
                    Thread.sleep(100);
                    if (n == 0) {
                       throw e;
                    }
                }
            }
            globalSession = null;
        }
    }

    /**
     * Branch register test.
     *
     * @param xid the xid
     * @throws Exception the exception
     */
    @ParameterizedTest
    @MethodSource("xidProvider")
    public void branchRegisterTest(String xid) throws Exception {
        core.branchRegister(BranchType.AT, resourceId, clientId, xid, "abc", lockKeys_1);
        globalSession = SessionHolder.findGlobalSession(xid);
        Assertions.assertEquals(globalSession.getSortedBranches().size(), 1);
    }

    /**
     * Branch report test.
     *
     * @param xid      the xid
     * @param branchId the branch id
     * @throws Exception the exception
     */
    @ParameterizedTest
    @MethodSource("xidAndBranchIdProvider")
    public void branchReportTest(String xid, Long branchId) throws Exception {
        core.branchReport(BranchType.AT, xid, branchId, BranchStatus.PhaseOne_Done, applicationData);
        globalSession = SessionHolder.findGlobalSession(xid);
        BranchSession branchSession = globalSession.getBranch(branchId);
        Assertions.assertEquals(branchSession.getStatus(), BranchStatus.PhaseOne_Done);
    }

    /**
     * Begin test.
     *
     * @throws Exception the exception
     */
    @Test
    public void beginTest() throws Exception {
        String xid = core.begin(applicationId, txServiceGroup, txName, timeout);
        globalSession = SessionHolder.findGlobalSession(xid);
        Assertions.assertNotNull(globalSession);

    }

    /**
     * Commit test.
     *
     * @param xid the xid
     * @throws Exception the exception
     */
    @ParameterizedTest
    @MethodSource("xidProvider")
    public void commitTest(String xid) throws Exception {
        GlobalStatus globalStatus = core.commit(xid);
        Assertions.assertNotEquals(globalStatus, GlobalStatus.Begin);
    }

    /**
     * Do global commit test.
     *
     * @param xid the xid
     * @throws Exception the exception
     */
    @ParameterizedTest
    @MethodSource("xidProvider")
    public void doGlobalCommitCommitTest(String xid) throws Exception {
        globalSession = SessionHolder.findGlobalSession(xid);
        BranchSession branchSession = SessionHelper.newBranchByGlobal(globalSession, BranchType.XA, resourceId,
            applicationData, "t1:1", clientId);
        globalSession.addBranch(branchSession);
        globalSession.changeBranchStatus(branchSession, BranchStatus.PhaseOne_Done);
        core.mockCore(BranchType.XA,
            new MockCore(BranchStatus.PhaseTwo_Committed, BranchStatus.PhaseOne_Done));
        core.doGlobalCommit(globalSession, true);
        Assertions.assertEquals(globalSession.getStatus(), GlobalStatus.Committed);
    }

    /**
     * Do global commit test.
     *
     * @param xid the xid
     * @throws Exception the exception
     */
    @ParameterizedTest
    @MethodSource("xidProvider")
    public void doGlobalCommitUnretryableTest(String xid) throws Exception {
        globalSession = SessionHolder.findGlobalSession(xid);
        BranchSession branchSession = SessionHelper.newBranchByGlobal(globalSession, BranchType.TCC, resourceId,
            applicationData, "t1:1", clientId);
        globalSession.addBranch(branchSession);
        globalSession.changeBranchStatus(branchSession, BranchStatus.PhaseOne_Done);
        core.mockCore(BranchType.TCC,
                new MockCore(BranchStatus.PhaseTwo_CommitFailed_Unretryable, BranchStatus.PhaseOne_Done));
        core.doGlobalCommit(globalSession, false);
        Assertions.assertEquals(globalSession.getStatus(), GlobalStatus.CommitFailed);
    }

    /**
     * Do global commit test.
     *
     * @param xid the xid
     * @throws Exception the exception
     */
    @ParameterizedTest
    @MethodSource("xidProvider")
    public void doGlobalCommitExpTest(String xid) throws Exception {
        globalSession = SessionHolder.findGlobalSession(xid);
        BranchSession branchSession = SessionHelper.newBranchByGlobal(globalSession, BranchType.XA, resourceId,
            applicationData, "t1:1", clientId);
        globalSession.addBranch(branchSession);
        globalSession.changeBranchStatus(branchSession, BranchStatus.PhaseOne_Done);
        core.mockCore(BranchType.XA,
                new MockCore(BranchStatus.PhaseOne_Timeout, BranchStatus.PhaseOne_Done));
        core.doGlobalCommit(globalSession, false);
        Assertions.assertEquals(globalSession.getStatus(), GlobalStatus.CommitRetrying);
    }

    /**
     * Roll back test.
     *
     * @param xid the xid
     * @throws Exception the exception
     */
    @ParameterizedTest
    @MethodSource("xidProvider")
    public void rollBackTest(String xid) throws Exception {
        GlobalStatus globalStatus = core.rollback(xid);
        Assertions.assertEquals(globalStatus, GlobalStatus.Rollbacked);
    }

    /**
     * Do global roll back test.
     *
     * @param xid the xid
     * @throws Exception the exception
     */
    @ParameterizedTest
    @MethodSource("xidProvider")
    public void doGlobalRollBackRollbackedTest(String xid) throws Exception {
        globalSession = SessionHolder.findGlobalSession(xid);
        BranchSession branchSession = SessionHelper.newBranchByGlobal(globalSession, BranchType.AT, resourceId,
            applicationData, "t1:1", clientId);
        globalSession.addBranch(branchSession);
        globalSession.changeBranchStatus(branchSession, BranchStatus.PhaseOne_Done);
        core.mockCore(BranchType.AT,
                new MockCore(BranchStatus.PhaseTwo_Committed, BranchStatus.PhaseTwo_Rollbacked));
        core.doGlobalRollback(globalSession, false);
        Assertions.assertEquals(globalSession.getStatus(), GlobalStatus.Begin);
    }

    /**
     * Do global roll back test.
     *
     * @param xid the xid
     * @throws Exception the exception
     */
    @ParameterizedTest
    @MethodSource("xidProvider")
    public void doGlobalRollBackUnretryableTest(String xid) throws Exception {
        globalSession = SessionHolder.findGlobalSession(xid);
        BranchSession branchSession = SessionHelper.newBranchByGlobal(globalSession, BranchType.AT, resourceId,
            applicationData, "t1:1", clientId);
        globalSession.addBranch(branchSession);
        globalSession.changeBranchStatus(branchSession, BranchStatus.PhaseOne_Done);
        core.mockCore(BranchType.AT, new MockCore(BranchStatus.PhaseTwo_Committed,
            BranchStatus.PhaseTwo_RollbackFailed_Unretryable));
        core.doGlobalRollback(globalSession, false);
        Assertions.assertEquals(globalSession.getStatus(), GlobalStatus.RollbackFailed);
    }

    /**
     * Do global roll back test.
     *
     * @param xid the xid
     * @throws Exception the exception
     */
    @ParameterizedTest
    @MethodSource("xidProvider")
    public void doGlobalRollBackRetryableExpTest(String xid) throws Exception {
        globalSession = SessionHolder.findGlobalSession(xid);
        BranchSession branchSession = SessionHelper.newBranchByGlobal(globalSession, BranchType.AT, resourceId,
            applicationData, "t1:1", clientId);
        globalSession.addBranch(branchSession);
        globalSession.changeBranchStatus(branchSession, BranchStatus.PhaseOne_Done);
        core.mockCore(BranchType.AT, new MockCore(BranchStatus.PhaseTwo_Committed,
            BranchStatus.PhaseTwo_RollbackFailed_Retryable));
        core.doGlobalRollback(globalSession, false);
        Assertions.assertEquals(globalSession.getStatus(), GlobalStatus.RollbackRetrying);
    }

    /**
     * Xid provider object [ ] [ ].
     *
     * @return the object [ ] [ ]
     * @throws Exception the exception
     */
    static Stream<Arguments> xidProvider() throws Exception {
        String xid = core.begin(applicationId, txServiceGroup, txName, timeout);
        Assertions.assertNotNull(xid);
        return Stream.of(
            Arguments.of(xid)
        );
    }

    /**
     * Xid and branch id provider object [ ] [ ].
     *
     * @return the object [ ] [ ]
     * @throws Exception the exception
     */
    static Stream<Arguments> xidAndBranchIdProvider() throws Exception {
        String xid = core.begin(applicationId, txServiceGroup, txName, timeout);
        Long branchId = core.branchRegister(BranchType.AT, resourceId, clientId, xid, null, lockKeys_2);
        Assertions.assertNotNull(xid);
        Assertions.assertTrue(branchId != 0);
        return Stream.of(
            Arguments.of(xid, branchId)
        );
    }

    /**
     * Release session manager.
     *
     * @throws Exception the exception
     */
    @AfterEach
    public void releaseSessionManager() throws Exception {
        Collection<GlobalSession> globalSessions = SessionHolder.getRootSessionManager().allSessions();
        Collection<GlobalSession> asyncGlobalSessions = SessionHolder.getAsyncCommittingSessionManager().allSessions();
        for (GlobalSession asyncGlobalSession : asyncGlobalSessions) {
            asyncGlobalSession.closeAndClean();
        }
        for (GlobalSession globalSession : globalSessions) {
            globalSession.closeAndClean();
        }
    }

    private static class MockCore extends AbstractCore {

        private BranchStatus commitStatus;
        private BranchStatus rollbackStatus;

        /**
         * Instantiates a new Mock resource manager inbound.
         *
         * @param commitStatus   the commit status
         * @param rollbackStatus the rollback status
         */
        public MockCore(BranchStatus commitStatus, BranchStatus rollbackStatus) {
            super(new DefaultCoordinatorTest.MockServerMessageSender());
            this.commitStatus = commitStatus;
            this.rollbackStatus = rollbackStatus;
        }

        @Override
        public BranchStatus branchCommit(GlobalSession globalSession, BranchSession branchSession) throws TransactionException {
            return commitStatus;
        }

        @Override
        public BranchStatus branchRollback(GlobalSession globalSession, BranchSession branchSession) throws TransactionException {
            return rollbackStatus;
        }

        @Override
        public BranchType getHandleBranchType() {
            return BranchType.AT;
        }
    }

}
