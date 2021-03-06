/*
 * Copyright 2020 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.hazelcast.jca;

import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import javax.resource.ResourceException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class HazelcastManagedConnectionMetaDataTest {

    private HazelcastManagedConnectionMetaData metaData = new HazelcastManagedConnectionMetaData();

    @Test
    public void testMetaDataFields() throws ResourceException {
        assertNull(metaData.getEISProductName());
        assertNull(metaData.getEISProductVersion());
        assertEquals("", metaData.getUserName());
        assertEquals(Integer.MAX_VALUE, metaData.getMaxConnections());
    }
}
