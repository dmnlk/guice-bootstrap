/*
 * Copyright 2010 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.embulk.guice;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.inject.Binder;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.PrivateModule;
import com.google.inject.Scopes;
import com.google.inject.Stage;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Test(singleThreaded = true)
public class TestLifeCycleManager
{
    private static final List<String> stateLog = new CopyOnWriteArrayList<>();

    @BeforeMethod
    public void setup()
    {
        stateLog.clear();
    }

    static void note(String str)
    {
        // I'm assuming that tests are run serially
        stateLog.add(str);
    }

    @Test
    public void testImmediateStarts()
            throws Exception
    {
        Injector injector = Guice.createInjector(
                Stage.PRODUCTION,
                new LifeCycleModule(),
                binder -> {
                    binder.bind(InstanceThatRequiresStart.class).in(Scopes.SINGLETON);
                    binder.bind(InstanceThatUsesInstanceThatRequiresStart.class).in(Scopes.SINGLETON);
                });

        LifeCycleManager lifeCycleManager = injector.getInstance(LifeCycleManager.class);
        lifeCycleManager.start();

        Assert.assertEquals(stateLog, ImmutableList.of("InstanceThatUsesInstanceThatRequiresStart:OK"));
    }

    @Test
    public void testPrivateModule()
            throws Exception
    {
        Injector injector = Guice.createInjector(
                Stage.PRODUCTION,
                new LifeCycleModule(),
                binder -> binder.install(new PrivateModule()
                {
                    @Override
                    protected void configure()
                    {
                        binder().bind(SimpleBase.class).to(SimpleBaseImpl.class).in(Scopes.SINGLETON);
                        binder().expose(SimpleBase.class);
                    }
                }));

        LifeCycleManager lifeCycleManager = injector.getInstance(LifeCycleManager.class);
        lifeCycleManager.start();
        Assert.assertEquals(stateLog, ImmutableList.of("postSimpleBaseImpl"));

        lifeCycleManager.destroy();
        Assert.assertEquals(stateLog, ImmutableList.of("postSimpleBaseImpl", "preSimpleBaseImpl"));
    }

    @Test
    public void testSubClassAnnotated()
            throws Exception
    {
        Injector injector = Guice.createInjector(
                Stage.PRODUCTION,
                new LifeCycleModule(),
                binder -> binder.bind(SimpleBase.class).to(SimpleBaseImpl.class).in(Scopes.SINGLETON));

        LifeCycleManager lifeCycleManager = injector.getInstance(LifeCycleManager.class);
        lifeCycleManager.start();
        Assert.assertEquals(stateLog, ImmutableList.of("postSimpleBaseImpl"));

        lifeCycleManager.destroy();

        Assert.assertEquals(stateLog, ImmutableList.of("postSimpleBaseImpl", "preSimpleBaseImpl"));
    }

    @Test
    public void testDeepDependency()
            throws Exception
    {
        Injector injector = Guice.createInjector(
                Stage.PRODUCTION,
                new LifeCycleModule(),
                binder -> {
                    binder.bind(AnInstance.class).in(Scopes.SINGLETON);
                    binder.bind(AnotherInstance.class).in(Scopes.SINGLETON);
                    binder.bind(DependentInstance.class).in(Scopes.SINGLETON);
                });

        injector.getInstance(AnotherInstance.class);

        LifeCycleManager lifeCycleManager = injector.getInstance(LifeCycleManager.class);
        lifeCycleManager.start();
        Assert.assertEquals(stateLog, ImmutableList.of("postDependentInstance"));

        lifeCycleManager.destroy();
        Assert.assertEquals(stateLog, ImmutableList.of("postDependentInstance", "preDependentInstance"));
    }

    @Test
    public void testIllegalMethods()
            throws Exception
    {
        try {
            Guice.createInjector(
                    Stage.PRODUCTION,
                    new Module()
                    {
                        @Override
                        public void configure(Binder binder)
                        {
                            binder.bind(IllegalInstance.class).in(Scopes.SINGLETON);
                        }
                    },
                    new LifeCycleModule());
            Assert.fail();
        }
        catch (CreationException dummy) {
            // correct behavior
        }
    }

    @Test
    public void testDuplicateMethodNames()
            throws Exception
    {
        Injector injector = Guice.createInjector(
                Stage.PRODUCTION,
                new Module()
                {
                    @Override
                    public void configure(Binder binder)
                    {
                        binder.bind(FooTestInstance.class).in(Scopes.SINGLETON);
                    }
                },
                new LifeCycleModule()
        );

        LifeCycleManager lifeCycleManager = injector.getInstance(LifeCycleManager.class);
        lifeCycleManager.start();
        lifeCycleManager.destroy();

        Assert.assertEquals(stateLog, ImmutableList.of("foo"));
    }

    @Test
    public void testJITInjection()
            throws Exception
    {
        Injector injector = Guice.createInjector(
                Stage.PRODUCTION,
                new LifeCycleModule(),
                binder -> {
                    binder.bind(AnInstance.class).in(Scopes.SINGLETON);
                    binder.bind(DependentInstance.class).in(Scopes.SINGLETON);
                });
        injector.getInstance(AnInstance.class);

        LifeCycleManager lifeCycleManager = injector.getInstance(LifeCycleManager.class);
        lifeCycleManager.start();
        lifeCycleManager.destroy();

        Assert.assertEquals(stateLog, ImmutableList.of("postDependentInstance", "preDependentInstance"));
    }

    @Test
    public void testNoPreDestroy()
            throws Exception
    {
        Injector injector = Guice.createInjector(
                Stage.PRODUCTION,
                new LifeCycleModule(),
                binder -> {
                    binder.bind(PostConstructOnly.class).in(Scopes.SINGLETON);
                    binder.bind(PreDestroyOnly.class).in(Scopes.SINGLETON);
                });
        injector.getInstance(PostConstructOnly.class);

        LifeCycleManager lifeCycleManager = injector.getInstance(LifeCycleManager.class);
        lifeCycleManager.start();
        Assert.assertEquals(stateLog, ImmutableList.of("makeMe"));

        lifeCycleManager.destroy();
        Assert.assertEquals(stateLog, ImmutableList.of("makeMe", "unmakeMe"));
    }

    @Test
    public void testModule()
            throws Exception
    {
        Injector injector = Guice.createInjector(
                Stage.PRODUCTION,
                new LifeCycleModule(),
                binder -> {
                    binder.bind(DependentBoundInstance.class).to(DependentInstanceImpl.class).in(Scopes.SINGLETON);

                    binder.bind(DependentInstance.class).in(Scopes.SINGLETON);
                    binder.bind(InstanceOne.class).in(Scopes.SINGLETON);
                    binder.bind(InstanceTwo.class).in(Scopes.SINGLETON);
                });

        LifeCycleManager lifeCycleManager = injector.getInstance(LifeCycleManager.class);
        lifeCycleManager.start();
        lifeCycleManager.destroy();

        Set<String> stateLogSet = Sets.newHashSet(stateLog);
        Assert.assertEquals(stateLogSet,
                Sets.newHashSet(
                        "postDependentBoundInstance",
                        "postDependentInstance",
                        "postMakeOne",
                        "postMakeTwo",
                        "preDestroyTwo",
                        "preDestroyOne",
                        "preDependentInstance",
                        "preDependentBoundInstance"));
    }
}
