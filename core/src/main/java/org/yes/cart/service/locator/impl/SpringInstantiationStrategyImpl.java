/*
 * Copyright 2009 Denys Pavlov, Igor Azarnyi
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.yes.cart.service.locator.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.yes.cart.service.locator.InstantiationStrategy;
import org.yes.cart.service.locator.ServiceLocator;

import java.util.Collections;
import java.util.Set;

/**
 * Used to locate services in local spring context.
 * When no protocol provided in the service url.
 * <p/>
 ** User: Igor Azarny iazarny@yahoo.com
 * Date: 09-May-2011
 * Time: 14:12:54
 */
public class SpringInstantiationStrategyImpl implements InstantiationStrategy, ApplicationContextAware {

    private static final Logger LOG = LoggerFactory.getLogger(SpringInstantiationStrategyImpl.class);

    private ApplicationContext applicationContext;

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<String> getProtocols() {
        return Collections.singleton(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T getInstance(final String serviceUrl,
                             final Class<T> iface,
                             final String loginName,
                             final String password) throws RuntimeException {
        LOG.debug("Get {} as {}", serviceUrl, iface.getName());
        return applicationContext.getBean(serviceUrl, iface);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings({"unchecked"})
    public void setApplicationContext(final ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }


    /**
     * Spring IoC.
     *
     * @param serviceLocator locator
     */
    public void setServiceLocator(final ServiceLocator serviceLocator) {
        serviceLocator.register(this);
    }


}
