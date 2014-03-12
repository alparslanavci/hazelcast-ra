/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jca;

import com.atomikos.icatch.jta.UserTransactionManager;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.TransactionalMap;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import com.hazelcast.test.annotation.SlowTest;
import com.hazelcast.transaction.TransactionContext;
import com.hazelcast.transaction.TransactionOptions;
import com.hazelcast.transaction.impl.TransactionAccessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.File;
import java.io.FilenameFilter;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hazelcast.test.HazelcastTestSupport.assertOpenEventually;
import static org.junit.Assert.*;

@RunWith(HazelcastSerialClassRunner.class)
@Category(SlowTest.class)
public class HazelcastXaTest {

    static final Random random = new Random(System.currentTimeMillis());
    static final ILogger logger = Logger.getLogger(HazelcastXaTest.class);

    UserTransactionManager tm = null;

    public void cleanAtomikosLogs(){
        try {
            File currentDir = new File(".");
            final File[] tmLogs = currentDir.listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    if (name.endsWith(".epoch") || name.startsWith("tmlog")) {
                        return true;
                    }
                    return false;
                }
            });
            for (File tmLog : tmLogs) {
                tmLog.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Before
    public void init() throws SystemException {
        cleanAtomikosLogs();
        tm = new UserTransactionManager();
        tm.setTransactionTimeout(60);
        Hazelcast.shutdownAll();
    }

    @After
    public void cleanup() {
        tm.close();
        cleanAtomikosLogs();
        Hazelcast.shutdownAll();
    }

    @Test
    public void testCommit() throws InterruptedException {
        HazelcastInstance instance1 = Hazelcast.newHazelcastInstance();
        HazelcastInstance instance2 = Hazelcast.newHazelcastInstance();


        final TransactionContext context = instance1.newTransactionContext();
        context.beginTransaction();
        context.getMap("map").put("key", "value");
        final com.hazelcast.transaction.impl.Transaction transaction = TransactionAccessor.getTransaction(context);
        transaction.prepare();

        instance1.shutdown();

        assertEquals("value", instance2.getMap("map").get("key"));

        try {
            transaction.rollback(); //for setting ThreadLocal of unfinished transaction
        } catch (Throwable ignored) {
        }
    }

    @Test
    public void testRecovery() throws Exception {
        HazelcastInstance instance1 = Hazelcast.newHazelcastInstance();
        HazelcastInstance instance2 = Hazelcast.newHazelcastInstance();
        HazelcastInstance instance3 = Hazelcast.newHazelcastInstance();

        TransactionContext context1 = instance1.newTransactionContext(TransactionOptions.getDefault().setDurability(2));
        XAResource xaResource = context1.getXaResource();
        final MyXid myXid = new MyXid();
        xaResource.start(myXid, 0);
        final TransactionalMap<Object, Object> map = context1.getMap("map");
        map.put("key", "value");
        xaResource.prepare(myXid);
        instance1.shutdown();

        assertNull(instance2.getMap("map").get("key"));

        instance1 = Hazelcast.newHazelcastInstance();
        TransactionContext context2 = instance1.newTransactionContext();
        xaResource = context2.getXaResource();
        final Xid[] recover = xaResource.recover(0);
        for (Xid xid : recover) {
            xaResource.commit(xid, false);
        }

        assertEquals("value", instance2.getMap("map").get("key"));

        try {
            context1.rollbackTransaction(); //for setting ThreadLocal of unfinished transaction
        } catch (Throwable ignored) {
        }
    }

    public static class MyXid implements Xid {

        public int getFormatId() {
            return 42;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return "GlobalTransactionId".getBytes();
        }

        @Override
        public byte[] getBranchQualifier() {
            return "BranchQualifier".getBytes();
        }
    }

    @Test
    public void testParallel() throws Exception {
        final HazelcastInstance instance = Hazelcast.newHazelcastInstance();

        final int size = 20;
        ExecutorService executorService = Executors.newFixedThreadPool(5);
        final CountDownLatch latch = new CountDownLatch(size);
        for (int i = 0; i < size; i++) {
            executorService.execute(new Runnable() {
                public void run() {
                    try {
                        txn(instance);
                    } catch (Exception e) {
                        logger.severe("Exception during txn", e);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        assertOpenEventually(latch, 20);
        final IMap m = instance.getMap("m");
        for (int i = 0; i < 10; i++) {
            assertFalse(m.isLocked(i));
        }
    }

    @Test
    public void testSequential() throws Exception {
        final HazelcastInstance instance = Hazelcast.newHazelcastInstance();
        txn(instance);
        txn(instance);
        txn(instance);
    }

    private void txn(HazelcastInstance instance) throws Exception {
        tm.begin();

        final TransactionContext context = instance.newTransactionContext();
        final XAResource xaResource = context.getXaResource();
        final Transaction transaction = tm.getTransaction();
        transaction.enlistResource(xaResource);

        boolean error = false;
        try {
            final TransactionalMap m = context.getMap("m");
            m.put(random.nextInt(10), "value");
        } catch (Exception e) {
            e.printStackTrace();
            error = true;
        } finally {
            close(error, xaResource);
        }
    }

    private void close(boolean error, XAResource... xaResource) throws Exception {

        int flag = XAResource.TMSUCCESS;

        // get the current tx
        Transaction tx = tm.getTransaction();
        // closeConnection
        if (error)
            flag = XAResource.TMFAIL;
        for (XAResource resource : xaResource) {
            tx.delistResource(resource, flag);
        }

        if (error)
            tm.rollback();
        else
            tm.commit();

    }

}
