/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2016 wcm.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package io.wcm.caconfig.extensions.contextpath.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.caconfig.resource.spi.ContextPathStrategy;
import org.apache.sling.caconfig.resource.spi.ContextResource;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.NameConstants;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.google.common.collect.ImmutableSet;

import io.wcm.caconfig.application.ApplicationFinder;
import io.wcm.caconfig.application.ApplicationInfo;

/**
 * {@link ContextPathStrategy} that detects context paths by matching parent pages against a list of allowed templates
 * for context root.
 * All page between min and max level up to a page with a page matching the templates are defined as context paths.
 */
@Component(service = ContextPathStrategy.class)
@Designate(ocd = RootTemplateContextPathStrategy.Config.class, factory = true)
public class RootTemplateContextPathStrategy implements ContextPathStrategy {

  @ObjectClassDefinition(name = "wcm.io Context-Aware Configuration Context Path Strategy: Root Templates",
      description = "Detects context paths by matching parent pages against a list of allowed templates for context root. "
          + "All page between min and max level up to a page with a page matching the templates are defined as context paths.")
  static @interface Config {

    @AttributeDefinition(name = "Templates",
        description = "List of template paths allowed for context root pages.")
    String[] templatePaths() default {};

    @AttributeDefinition(name = "Min. Level",
        description = "Minimum allowed absolute parent level. Example: Absolute parent level 1 of '/foo/bar/test' is '/foo/bar'.")
    int minLevel() default 1;

    @AttributeDefinition(name = "Max. Level",
        description = "Maximum allowed absolute parent level. Example: Absolute parent level 1 of '/foo/bar/test' is '/foo/bar'.")
    int maxLevel() default 5;

    @AttributeDefinition(name = "Context path expression",
        description = "Expression to match context paths. Only context paths matching this expression are allowed.")
    String contextPathRegex() default "^(/content/.+)$";

    @AttributeDefinition(name = "Config path pattern",
        description = "Expression to derive the config path from the context path. Regex group references like $1 can be used.")
    String configPathPattern() default "/conf$1";

    @AttributeDefinition(name = "Application ID",
        description = "Optional: Apply context path strategy only for context resources associated with the given Application ID.")
    String applicationId() default "";

    @AttributeDefinition(name = "Service Ranking",
        description = "Priority of configuration override providers (higher = higher priority).")
    int service_ranking() default 0;

  }

  @Reference
  private ApplicationFinder applicationFinder;

  private Set<String> templatePaths;
  private int minLevel;
  private int maxLevel;
  private Pattern contextPathRegex;
  private String configPathPattern;
  private String applicationId;

  private static final Logger log = LoggerFactory.getLogger(RootTemplateContextPathStrategy.class);

  @Activate
  void activate(Config config) {
    templatePaths = config.templatePaths() != null ? ImmutableSet.copyOf(config.templatePaths()) : Collections.<String>emptySet();
    minLevel = config.minLevel();
    maxLevel = config.maxLevel();
    try {
      contextPathRegex = Pattern.compile(config.contextPathRegex());
    }
    catch (PatternSyntaxException ex) {
      log.warn("Invalid context path regex: " + config.contextPathRegex(), ex);
    }
    configPathPattern = config.configPathPattern();
    applicationId = config.applicationId();
  }

  @Override
  public Iterator<ContextResource> findContextResources(Resource resource) {
    if (!isValidConfig() || !matchesApplication(resource)) {
      return Collections.emptyIterator();
    }

    List<String> contextPathCandidats = getContextPathCandidates(resource);
    List<ContextResource> contextResources = new ArrayList<>();
    for (String contextPath : contextPathCandidats) {
      Resource contextResource = resource.getResourceResolver().getResource(contextPath);
      if (contextResource != null) {
        String configRef = deriveConfigRef(contextPath);
        if (configRef != null) {
          contextResources.add(new ContextResource(contextResource, configRef));
        }
      }
    }
    Collections.reverse(contextResources);
    return contextResources.iterator();
  }

  private boolean isValidConfig() {
    return !templatePaths.isEmpty()
        && contextPathRegex != null
        && StringUtils.isNotBlank(configPathPattern);
  }

  private boolean matchesApplication(Resource resource) {
    if (StringUtils.isBlank(applicationId)) {
      return true;
    }
    ApplicationInfo appInfo = applicationFinder.find(resource);
    return (appInfo != null && StringUtils.equals(appInfo.getApplicationId(), this.applicationId));
  }

  private List<String> getContextPathCandidates(Resource resource) {
    List<String> candidates = new ArrayList<>();
    PageManager pageManager = resource.getResourceResolver().adaptTo(PageManager.class);
    Page page = pageManager.getContainingPage(resource);
    if (page != null) {
      for (int level = minLevel; level <= maxLevel; level++) {
        Page rootPage = page.getAbsoluteParent(level);
        if (rootPage != null) {
          String templatePath = rootPage.getProperties().get(NameConstants.PN_TEMPLATE, String.class);
          if (templatePath != null) {
            candidates.add(rootPage.getPath());
            if (templatePaths.contains(templatePath)) {
              break;
            }
          }
        }
      }
    }
    return candidates;
  }

  private String deriveConfigRef(String contextPath) {
    Matcher matcher = contextPathRegex.matcher(contextPath);
    if (matcher.matches()) {
      return matcher.replaceAll(configPathPattern);
    }
    else {
      return null;
    }
  }

}
