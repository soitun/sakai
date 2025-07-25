/**
 * Copyright (c) 2003-2019 The Apereo Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://opensource.org/licenses/ecl2
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sakaiproject.sitemanage.impl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.entity.api.EntityProducer;
import org.sakaiproject.entity.api.EntityTransferrer;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.event.api.EventTrackingService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.shortenedurl.api.ShortenedUrlService;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SitePage;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.site.api.ToolConfiguration;
import org.sakaiproject.sitemanage.api.SiteManageConstants;
import org.sakaiproject.sitemanage.api.SiteManageService;
import org.sakaiproject.sitemanage.api.UserNotificationProvider;
import org.sakaiproject.thread_local.api.ThreadLocalManager;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.tool.api.Tool;
import org.sakaiproject.tool.api.ToolManager;
import org.sakaiproject.tool.api.ToolSession;
import org.sakaiproject.user.api.PreferencesService;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.util.ArrayUtil;
import org.sakaiproject.util.Web;
import org.sakaiproject.util.api.LinkMigrationHelper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.sakaiproject.event.api.NotificationService;
import org.tsugi.lti13.LTICustomVars;
import org.sakaiproject.authz.api.Role;

@Slf4j
public class SiteManageServiceImpl implements SiteManageService {

    @Setter private ContentHostingService contentHostingService;
    @Setter private EntityManager entityManager;
    @Setter private EventTrackingService eventTrackingService;
    @Setter private LinkMigrationHelper linkMigrationHelper;
    @Setter private PreferencesService preferencesService;
    @Setter private SecurityService securityService;
    @Setter private ServerConfigurationService serverConfigurationService;
    @Setter private SessionManager sessionManager;
    @Setter private ShortenedUrlService shortenedUrlService;
    @Setter private SiteService siteService;
    @Setter private ThreadLocalManager threadLocalManager;
    @Setter private ToolManager toolManager;
    @Setter private TransactionTemplate transactionTemplate;
    @Setter private UserDirectoryService userDirectoryService;
    @Setter private UserNotificationProvider userNotificationProvider;

    @Setter private Integer siteImportThreadCount;

    private ExecutorService executorService;
    private Set<String> currentSiteImports;

    public void init() {
        // while this Set isn't cluster wide sessions are node specific
        // so this is only unsafe for more than one session performing an import on the same site
        // which is a really low percentage
        currentSiteImports = new ConcurrentSkipListSet<>();
        executorService = Executors.newFixedThreadPool(siteImportThreadCount);
    }

    public void destroy() {
        try {
            executorService.shutdown();
            executorService.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            log.warn("Site Import executor did not shutdown gracefully");
        } finally {
            if (!executorService.isTerminated()) {
                executorService.shutdownNow();
            }
        }
    }

    @Override
    public boolean importToolsIntoSiteThread(final Site site, final List<String> existingTools, final Map<String, List<String>> importTools, final Map<String, Map<String, List<String>>> toolItemMap, final Map<String, Map<String, List<String>>> toolOptions, final boolean cleanup) {

        final User user = userDirectoryService.getCurrentUser();
        final Locale locale = preferencesService.getLocale(user.getId());
        final Session session = sessionManager.getCurrentSession();
        final ToolSession toolSession = sessionManager.getCurrentToolSession();
        final String id = site.getId();

        Runnable siteImportTask = () -> {
            // capture the previous session info to this thread
            sessionManager.setCurrentSession(session);
            sessionManager.setCurrentToolSession(toolSession);
            
			// This importSites var is solely for event tracking
			String importSites = "";
			for (Map.Entry<String, List<String>> entry : importTools.entrySet()) {
				if (importSites.length() >= 255) {
					break;
				}
				for (String data : entry.getValue() ) {
					String temp = StringUtils.joinWith(", ", importSites, data);
					if (!importSites.contains(data) && temp.length() < 255) {
						importSites = temp;
					}
				}
			}
			eventTrackingService.post(eventTrackingService.newEvent(SiteService.EVENT_SITE_IMPORT_START, importSites, id, false, NotificationService.NOTI_OPTIONAL));
			
			try {
                log.info("Started Site Import for the site {}", id);
                importToolsIntoSite(site, existingTools, importTools, toolItemMap, toolOptions, cleanup);
                log.info("Finished Site Import for the site {}", id);
            } catch (Exception e) {
                log.warn("Site Import Task encountered an exception for site {}, {}", id, e.getMessage());
            } finally {
                currentSiteImports.remove(id);
            }

            if (serverConfigurationService.getBoolean(SiteManageConstants.SAK_PROP_IMPORT_NOTIFICATION, true)) {
                userNotificationProvider.notifySiteImportCompleted(user.getEmail(), locale, id, site.getTitle());
            }
            eventTrackingService.post(eventTrackingService.newEvent(SiteService.EVENT_SITE_IMPORT_END, importSites, id, false, NotificationService.NOTI_OPTIONAL));

            // clear any sakai related state from the thread before returning it
            threadLocalManager.clear();
        };

        // only if the siteId was added to the list do we start the task
        if (currentSiteImports.add(id)) {
            try {
                executorService.execute(siteImportTask);
                return true;
            } catch (RejectedExecutionException ree) {
                log.warn("Site Import Task was rejected by the executor, {}", ree.getMessage());
                currentSiteImports.remove(id);
            }
        }
        return false;
    }

    @Override
    public void importToolContent(String oSiteId, Site site, boolean bypassSecurity) {

        SecurityAdvisor securityAdvisor = null;
        String nSiteId = site.getId();

        try {
            // import tool content
            if (bypassSecurity) {
                // importing from template, bypass the permission checking:
                // temporarily allow the user to read and write from assignments (asn.revise permission)
                securityAdvisor = (userId, function, reference) -> SecurityAdvisor.SecurityAdvice.ALLOWED;
                securityService.pushAdvisor(securityAdvisor);
            }

            List<SitePage> pageList = site.getPages();
            Set<String> toolsCopied = new HashSet<>();


            Map<String, String> transversalMap = new HashMap<>();
            
            // Add code to copy tool permissions from source site to destination site
            log.debug("About to copy tool permissions from site {} to site {}", oSiteId, nSiteId);
            copyToolPermissions(oSiteId, nSiteId);

            if (pageList != null) {
                for (SitePage page : pageList) {
                    List<ToolConfiguration> pageToolList = page.getTools();
                    if ((pageToolList != null) && !pageToolList.isEmpty()) {
                        Tool tool = pageToolList.get(0).getTool();
                        if (tool != null) { // ignore page if the tool can't be retrieved
                            String toolId = tool.getId();
                            if (StringUtils.equalsIgnoreCase(toolId, SiteManageConstants.RESOURCES_TOOL_ID)) {
                                // special handling for resources
                                transversalMap.putAll(
                                        transferCopyEntities(toolId,
                                                contentHostingService.getSiteCollection(oSiteId),
                                                contentHostingService.getSiteCollection(nSiteId),
                                                Collections.EMPTY_LIST,
                                                Collections.EMPTY_LIST,
                                                false));
                                transversalMap.putAll(getDirectToolUrlEntityReferences(toolId, oSiteId, nSiteId));

                            } else if (StringUtils.equalsIgnoreCase(toolId, SiteManageConstants.SITE_INFO_TOOL_ID)) {
                                // handle Home tool specially, need to update the site infomration display url if needed
                                String newSiteInfoUrl = transferSiteResource(oSiteId, nSiteId, site.getInfoUrl());
                                site.setInfoUrl(newSiteInfoUrl);
                                saveSite(site);
                            } else if (StringUtils.isNotBlank(toolId)) {
                                // all other tools
                                if (!toolsCopied.contains(toolId)) {
                                    transversalMap.putAll(transferCopyEntities(toolId, oSiteId, nSiteId, Collections.EMPTY_LIST, Collections.EMPTY_LIST, false));
                                    transversalMap.putAll(getDirectToolUrlEntityReferences(toolId, oSiteId, nSiteId));
                                    toolsCopied.add(toolId);
                                }
                            }
                        } else {
                            log.warn("Skipping page {}, because the tool could not be retrieved", page.getId());
                        }
                    }
                }
                // after all site pages have been processed time to update references for each tool copied
                toolsCopied.forEach(t -> updateEntityReferences(t, nSiteId, transversalMap, site));
            }
        } catch (Exception e) {
            log.warn("Error during tool import for site {}, {}", nSiteId, e.getMessage());
        } finally {
            if (bypassSecurity) {
                securityService.popAdvisor(securityAdvisor);
            }
        }
    }

    @Override
    public boolean isAddMissingToolsOnImportEnabled() {
        return serverConfigurationService.getBoolean("site.setup.import.addmissingtools", true);
    }

    @Override
    public String transferSiteResource(String oSiteId, String nSiteId, String siteAttribute) {
        String rv = siteAttribute;

        String access = serverConfigurationService.getAccessUrl();
        if (siteAttribute != null && siteAttribute.contains(oSiteId) && access != null) {
            Reference ref = null;
            try {
                URI accessUrl = new URI(access);
                URI url = new URI(siteAttribute);
                String path = url.getPath();
                String accessPath = accessUrl.getPath();

                // stripe out the access url, get the relative form of "url"
                String contentRef = path.replaceAll(accessPath, "");

                ref = entityManager.newReference(contentRef);

                ContentResource resource = contentHostingService.getResource(ref.getId());
                // the new resource
                ContentResource nResource = null;
                String nResourceId = resource.getId().replaceAll(oSiteId, nSiteId);
                try {
                    nResource = contentHostingService.getResource(nResourceId);
                } catch (Exception e) {
                    log.warn("Cannot find resource with id={} copying it from the original resource, {}", nResourceId, e.getMessage());
                    // copy the resource then
                    try {
                        nResourceId = contentHostingService.copy(resource.getId(), nResourceId);
                        nResource = contentHostingService.getResource(nResourceId);
                    } catch (Exception ee) {
                        log.warn("Something happened copying the resource with id={}, {}", resource.getId(), ee.getMessage());
                    }
                }

                // get the new resource url
                rv = nResource != null ? nResource.getUrl(false) : "";

            } catch (URISyntaxException use) {
                log.warn("Couldn't update site resource: {}, {}", siteAttribute, use.getMessage());
            } catch (Exception e) {
                log.warn("Cannot find resource with ref={}, {}", ref.getReference(), e.getMessage());
            }
        }

        return rv;
    }
    
    /**
     * Helper to copy the tool title from one site to another.
     * <p>
     * Note that it does NOT save the site. The caller must handle this.
     *
     * @param toSite the site to update
     * @param fromSiteId the site id to copy from
     * @param importTools the tool id to copy
     * @return site the updated site object
     */
    private Site setToolTitle(Site toSite, String fromSiteId, String toolId) {
        try {
            if (toSite.getToolForCommonId(toolId) != null) {
                Site fromSite = siteService.getSite(fromSiteId);
                for (ToolConfiguration tc : fromSite.getTools(toolId)) {
                    try {
                        ToolConfiguration toTc = toSite.getToolForCommonId(toolId);
                        String title = tc.getContainingPage().getTitle();
                        if (SiteManageConstants.GRADEBOOK_TOOL_ID.equals(toolId)) {
                            title = toolManager.getLocalizedToolProperty(SiteManageConstants.GRADEBOOK_TOOL_ID, "title");
                        }
                        toTc.getContainingPage().setTitle(title);
                        toTc.getContainingPage().setTitleCustom(tc.getContainingPage().getTitleCustom());
                        toTc.setTitle(tc.getTitle());
                    } catch (Exception e) {
                        log.warn("Can't set tool title, {}", e.getMessage());
                    }
                }
            } else {
              log.debug("Site : {} does not have the tool: {}", toSite.getTitle(), toolId);
            }
        } catch (Exception e) {
            log.warn("Error setting tool title from {} to {} : {}", fromSiteId, toSite.getId(), e.getMessage());
        }
        return toSite;
    }

    /**
     * Helper to add a tool to a site if the site does not contain an instance of the tool.
     * <p>
     * Note that it does NOT save the site. The caller must handle this.
     *
     * @param site   the site to check
     * @param toolId the tool to add (eg sakai.resources)
     * @return site the updated site object
     */
    private Site addToolToSiteIfMissing(Site site, String toolId) {

        if (site.getToolForCommonId(toolId) != null) {
            return site;
        }

        log.debug("Adding tool to site: {}, tool: {}", site.getId(), toolId);

        SitePage page = site.addPage(); //inherit the tool's title.

        ToolConfiguration tool = page.addTool();
        tool.setTool(toolId, toolManager.getTool(toolId));
        tool.setTitle(toolManager.getTool(toolId).getTitle());

        return site;
    }

    /**
     * Copies the site information from one site ot another.
     * @param fromSiteId    the source site
     * @param toSiteId      the destinatination site
     * @return the site with the updated site information
     */
    private Site copySiteInformation(String fromSiteId, String toSiteId) {
        Site toSite = null;
        try {
            Site fromSite = siteService.getSite(fromSiteId);
            toSite = siteService.getSite(toSiteId);
            toSite.setDescription(fromSite.getDescription());
            toSite.setInfoUrl(fromSite.getInfoUrl());
            saveSite(toSite);
        } catch (IdUnusedException iue) {
            log.warn("Site not found, {}", iue.getMessage());
        }
        return toSite;
    }

    @Override
    public void importToolsIntoSite(Site site, List<String> toolIds, Map<String, List<String>> importTools, Map<String, Map<String, List<String>>> toolItemMap, Map<String, Map<String, List<String>>> toolOptions, boolean cleanup) {

		if (MapUtils.isEmpty(importTools) && MapUtils.isEmpty(toolItemMap)) {
			return;
		}

		//if add missing tools is enabled, add the tools ito the site before importing content
		if (isAddMissingToolsOnImportEnabled()) {

			//add the toolId lists into a set to remove dupes
			Set<String> toolsToProcess = new HashSet<>(toolIds);
			toolsToProcess.addAll(importTools.keySet());

			//now compare what we have to what we need to add
			final List<String> selectedTools = new ArrayList<>(toolsToProcess);
			log.debug("selectedTools: {}", selectedTools);

			List<String> missingToolIds = new ArrayList<>(selectedTools);
			missingToolIds.removeAll(toolIds);
			log.debug("missingToolIds: {}", missingToolIds);

			//and add
			for (String missingToolId : missingToolIds) {
				site = addToolToSiteIfMissing(site, missingToolId);
				saveSite(site);
			}

			//now update toolIds to match importTools so that the content is imported
			toolIds.clear();

			if (MapUtils.isNotEmpty(importTools)) {
				toolIds.addAll(importTools.keySet());
			}

			if (MapUtils.isNotEmpty(toolItemMap)) {
				toolIds.addAll(toolItemMap.keySet());
			}
		}

		//set custom title
		if (cleanup) {
			log.debug("allToolIds: {}", toolIds);
			for (String toolId : toolIds) {
				try {
					String siteFromId = importTools.get(toolId).get(0);
					site = setToolTitle(site, siteFromId, toolId);
					saveSite(site);
				} catch (Exception e) {
					log.warn("Problem with {}: {}", toolId, e.getMessage());
				}
			}
		}

		Set<String> siteIds = new LinkedHashSet<>();
		Map<String, String> transversalMap = new HashMap<>();
		final String toSiteId = site.getId();

		// import resources first
		boolean resourcesImported = false;
		for (int i = 0; i < toolIds.size() && !resourcesImported; i++) {
			String toolId = toolIds.get(i);
			Map<String, List<String>> siteItems = toolItemMap.getOrDefault(toolId, Collections.EMPTY_MAP);

			if (StringUtils.equalsIgnoreCase(toolId, SiteManageConstants.RESOURCES_TOOL_ID)) {
				List<String> fullyImportedSiteIds = importTools.getOrDefault(toolId, Collections.EMPTY_LIST);
				for (String fromSiteId : fullyImportedSiteIds) {
					SecurityAdvisor securityAdvisor = pushAdvisorIfTemplateSite(fromSiteId);
					try {
						String fromSiteCollectionId = contentHostingService.getSiteCollection(fromSiteId);
						String toSiteCollectionId = contentHostingService.getSiteCollection(toSiteId);
						transversalMap.putAll(transferCopyEntities(toolId, fromSiteCollectionId, toSiteCollectionId, siteItems.get(fromSiteId), Collections.EMPTY_LIST, cleanup));
						transversalMap.putAll(getDirectToolUrlEntityReferences(toolId, fromSiteId, toSiteId));
						siteIds.add(fromSiteId);
						resourcesImported = true;
					} finally {
						if (securityAdvisor != null) {
							securityService.popAdvisor(securityAdvisor);
						}
					}
				}

				for (String fromSiteId : siteItems.keySet()) {
					SecurityAdvisor securityAdvisor = pushAdvisorIfTemplateSite(fromSiteId);
					try {
						String fromSiteCollectionId = contentHostingService.getSiteCollection(fromSiteId);
						String toSiteCollectionId = contentHostingService.getSiteCollection(toSiteId);
						transversalMap.putAll(transferCopyEntities(toolId, fromSiteCollectionId, toSiteCollectionId, siteItems.get(fromSiteId), Collections.EMPTY_LIST, cleanup));
						transversalMap.putAll(getDirectToolUrlEntityReferences(toolId, fromSiteId, toSiteId));
						siteIds.add(fromSiteId);
						resourcesImported = true;
					} finally {
						if (securityAdvisor != null) {
							securityService.popAdvisor(securityAdvisor);
						}
					}
				}
			}
		}

		// Now gradebook. Several tools depend on gradebook and may well bring in gradebook items. If
		// the gradebook import happens after that, in replace mode, the gradebook import will clean
		// out all the items imported by the other tools, like Assignments.
		for (String toolId : toolIds) {
			if (StringUtils.equalsIgnoreCase(toolId, SiteManageConstants.GRADEBOOK_TOOL_ID)) {
				Map<String, List<String>> siteItems = toolItemMap.getOrDefault(toolId, Collections.EMPTY_MAP);

				List<String> fullyImportedSiteIds = importTools.getOrDefault(toolId, Collections.EMPTY_LIST);
				for (String fromSiteId : fullyImportedSiteIds) {
					doImport(transversalMap, site, toolId, siteIds, fromSiteId, toSiteId, siteItems, Collections.EMPTY_MAP, cleanup, false);
				}

				for (String fromSiteId : siteItems.keySet()) {
					doImport(transversalMap, site, toolId, siteIds, fromSiteId, toSiteId, siteItems, Collections.EMPTY_MAP, cleanup, false);
				}
			}
		}

		// Now calendar. Same reason as gradebook.
		for (String toolId : toolIds) {
			if (StringUtils.equalsIgnoreCase(toolId, SiteManageConstants.CALENDAR_TOOL_ID) && importTools.containsKey(toolId)) {
				Map<String, List<String>> siteItems = toolItemMap.getOrDefault(toolId, Collections.EMPTY_MAP);
				for (String fromSiteId : importTools.get(toolId)) {
					doImport(transversalMap, site, toolId, siteIds, fromSiteId, toSiteId, siteItems, Collections.EMPTY_MAP, cleanup, false);
				}
			}
		}

		// Now import the rest of the tools
		for (String toolId : toolIds) {
			if (!StringUtils.equalsIgnoreCase(toolId, SiteManageConstants.RESOURCES_TOOL_ID)
					&& !StringUtils.equalsIgnoreCase(toolId, SiteManageConstants.GRADEBOOK_TOOL_ID)
					&& !StringUtils.equalsIgnoreCase(toolId, SiteManageConstants.CALENDAR_TOOL_ID)) {

				Map<String, List<String>> siteItems = toolItemMap.getOrDefault(toolId, Collections.EMPTY_MAP);
				Map<String, List<String>> siteOptions = toolOptions.getOrDefault(toolId, Collections.EMPTY_MAP);

				List<String> fullyImportedSiteIds = importTools.getOrDefault(toolId, Collections.EMPTY_LIST);
				for (String fromSiteId : fullyImportedSiteIds) {
					doImport(transversalMap, site, toolId, siteIds, fromSiteId, toSiteId, siteItems, siteOptions, cleanup, true);
				}

				for (String fromSiteId : siteItems.keySet()) {
					doImport(transversalMap, site, toolId, siteIds, fromSiteId, toSiteId, siteItems, siteOptions, cleanup, true);
				}
			}
		}

		// Update entity references
		for (String toolId : toolIds) {
			if (importTools.containsKey(toolId)) {
				updateEntityReferences(toolId, toSiteId, transversalMap, site);
			}
		}

		// Copy permissions from source sites to destination site
		for (String fromSiteId : siteIds) {
			log.debug("Copying permissions from site {} to site {}", fromSiteId, toSiteId);
			copyToolPermissions(fromSiteId, toSiteId);
		}

		// Handle the Context.id.history
		mergeContextIdHistory(siteIds, site);
	}

	private void doImport(Map<String, String> transversalMap, Site site, String toolId, Set<String> siteIds, String fromSiteId, String toSiteId, Map<String, List<String>> siteItems, Map<String, List<String>> siteOptions, boolean cleanup, boolean checkSiteInfo) {

		if (checkSiteInfo && SiteManageConstants.SITE_INFO_TOOL_ID.equals(toolId)) {
			site = copySiteInformation(fromSiteId, toSiteId);
			siteIds.add(fromSiteId);
		} else {
			SecurityAdvisor securityAdvisor = pushAdvisorIfTemplateSite(fromSiteId);
			try {
				transversalMap.putAll(transferCopyEntities(toolId, fromSiteId, toSiteId, siteItems.get(fromSiteId), siteOptions.get(fromSiteId), cleanup));
				transversalMap.putAll(getDirectToolUrlEntityReferences(toolId, fromSiteId, toSiteId));
				siteIds.add(fromSiteId);
			} finally {
				if (securityAdvisor != null) {
					securityService.popAdvisor(securityAdvisor);
				}
			}
		}
	}

    private SecurityAdvisor pushAdvisorIfTemplateSite(String fromSiteId) {

        SecurityAdvisor securityAdvisor = null;
        try {
            Site fromSite = siteService.getSite(fromSiteId);
            ResourceProperties fromSiteProps = fromSite.getProperties();
            boolean isTemplate = StringUtils.equals(fromSiteProps.getProperty("template"), "true");
            if (isTemplate) {
                securityAdvisor = (u, f, r) -> SecurityAdvisor.SecurityAdvice.ALLOWED;
                securityService.pushAdvisor(securityAdvisor);
            }
        } catch (IdUnusedException idue) {
            log.error("No site for id {}", fromSiteId);
        }

        return securityAdvisor;
    }

    /**
     * Compute the Context.id.history for the new site and insert it
     *
     * @param siteIds  a set of site ids to merge into the Context.id.history
     * @param site       the site to save
     */
    private void mergeContextIdHistory(Set<String> siteIds, Site site) {
        Set<String> new_set = new LinkedHashSet<String>();
        for(String fromSiteId : siteIds) {
            try {
                Site fromSite = siteService.getSite(fromSiteId);
                ResourceProperties rp = fromSite.getProperties();
                String old_id_history = rp.getProperty(LTICustomVars.CONTEXT_ID_HISTORY);
                if ( StringUtils.isBlank(old_id_history) ) old_id_history = "";
                List<String> old_id_list = Arrays.asList(old_id_history.split(","));

                // Pull in the old ids.
                for ( String old_id : old_id_list ) {
                    if ( StringUtils.isNotBlank(old_id) ) new_set.add(old_id);
                }
            } catch (Exception e) {
                log.warn("Can't get site, {}", e.getMessage());
                continue;
            }

            // Add the actual containing site
            new_set.add(fromSiteId);
        }

        if ( new_set.size() < 1 ) return;

        String id_history = String.join(",", new_set);

        // Grab our own edit to save the property - if you use the site variable and save it
        // you will perturb an import in progress when it is running in a background thread
        try {
            Site tmpSite = siteService.getSite(site.getId());
            ResourcePropertiesEdit rp = tmpSite.getPropertiesEdit();
            rp.addProperty(LTICustomVars.CONTEXT_ID_HISTORY, id_history);
            saveSite(tmpSite);
        } catch (IdUnusedException iue) {
            log.warn("Site not found, {}", iue.getMessage());
        }
    }

    /**
     * Save a site
     * @param site  the site to save
     */
    private void saveSite(Site site) {
        try {
            siteService.save(site);
        } catch (IdUnusedException iue) {
            log.warn("The site {} must exist in order to update it, {}", site.getId(), iue.getMessage());
        } catch (PermissionException pe) {
            log.warn("The user cannot update the site {}, {}", site.getId(), pe.getMessage());
        }
    }

    /**
     * Transfer a copy of all entites from another context for any entity
     * producer that claims this tool id.
     *
     * @param toolId      The tool id.
     * @param fromContext The context to import from.
     * @param toContext   The context to import into.
     */
    private Map<String, String> transferCopyEntities(String toolId, String fromContext, String toContext, List<String> toolItems, List<String> toolOptions, boolean cleanup) {

        Map<String, String> transversalMap = new HashMap<>();

        // offer to all EntityProducers
        for (EntityProducer ep : entityManager.getEntityProducers()) {
            if (ep instanceof EntityTransferrer) {
                try {
                    EntityTransferrer et = (EntityTransferrer) ep;

                    // if this producer claims this tool id
                    if (ArrayUtil.contains(et.myToolIds(), toolId)) {
                        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                            @Override
                            protected void doInTransactionWithoutResult(TransactionStatus status) {

                                Map<String, String> entityMap
                                    = et.transferCopyEntities(
                                        fromContext, toContext, toolItems, toolOptions, cleanup);
                                if (entityMap != null) {
                                    transversalMap.putAll(entityMap);
                                }
                            }
                        });
                    }
                } catch (Exception e) {
                    log.error("Error encountered while transferring data for producer: [{}] from: [{}] to: [{}]", ep.getLabel(), fromContext, toContext, e);
                }
            }
        }
        return transversalMap;
    }

    private Map<String, String> getDirectToolUrlEntityReferences(String toolId, String fromSiteId, String toSiteId) {

        Map<String, String> transversalMap = new HashMap<>();
        // record direct URL for this tool in old and new sites, so anyone using the URL in HTML text will
        // get a proper update for the HTML in the new site
        // Some tools can have more than one instance. Because getTools should always return tools
        // in order, we can assume that if there's more than one instance of a tool, the instances
        // correspond

        Collection<ToolConfiguration> fromTools = null;
        Collection<ToolConfiguration> toTools = null;
        try {
            Site fromSite = siteService.getSite(fromSiteId);
            Site toSite = siteService.getSite(toSiteId);
            fromTools = fromSite.getTools(toolId);
            toTools = toSite.getTools(toolId);
        } catch (Exception e) {
            log.warn("Can't get site, {}", e.getMessage());
        }

        // getTools appears to return tools in order. So we should be able to match them
        if (fromTools != null && toTools != null) {
            Iterator<ToolConfiguration> toToolIt = toTools.iterator();
            // step through tools in old and new site in parallel
            // I believe the first time this is called for a given
            // tool all instances will be copied, but stop if not
            // all instances have been copied yet
            for (ToolConfiguration fromTool : fromTools) {
                if (toToolIt.hasNext()) {
                    ToolConfiguration toTool = toToolIt.next();
                    String fromUrl = serverConfigurationService.getPortalUrl() + "/directtool/" + Web.escapeUrl(fromTool.getId()) + "/";
                    String toUrl = serverConfigurationService.getPortalUrl() + "/directtool/" + Web.escapeUrl(toTool.getId()) + "/";
                    transversalMap.putIfAbsent(fromUrl, toUrl);
                    if (shortenedUrlService.shouldCopy(fromUrl)) {
                        fromUrl = shortenedUrlService.shorten(fromUrl, false);
                        toUrl = shortenedUrlService.shorten(toUrl, false);
                        if (fromUrl != null && toUrl != null)
                            transversalMap.put(fromUrl, toUrl);
                    }
                } else {
                    break;
                }
            }
        }
        return transversalMap;
    }

    /**
     * Updates the references to entities that have been copied.
     * @param toolId         the tool continaing references to be updated
     * @param toContext      the context (site) related to the references that will be updated
     * @param transversalMap Map containing new references -> old references
     * @param site           the new site
     */
    private void updateEntityReferences(String toolId, String toContext, Map<String, String> transversalMap, Site site) {
        if (toolId.equalsIgnoreCase(SiteManageConstants.SITE_INFO_TOOL_ID)) {
            updateSiteInfoToolEntityReferences(transversalMap, site);
        } else {
            for (EntityProducer ep : entityManager.getEntityProducers()) {
                if (ep instanceof EntityTransferrer) {
                    try {
                        EntityTransferrer et = (EntityTransferrer) ep;

                        // if this producer claims this tool id
                        if (ArrayUtil.contains(et.myToolIds(), toolId)) {
                            transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                                @Override
                                protected void doInTransactionWithoutResult(TransactionStatus status) {
                                    et.updateEntityReferences(toContext, transversalMap);
                                }
                            });
                        }
                    } catch (Exception e) {
                        log.error("Error encountered while asking EntityTransfer to updateEntityReferences at site: {}", toContext, e);
                    }
                }
            }
        }
    }

    /**
     * Updates links in the site information to point to the new site.
     * It uses the {@link LinkMigrationHelper} service to migrate the links to the new site.
     * @param transversalMap the map of tools
     * @param site           the new site where links should be updated
     */
    private void updateSiteInfoToolEntityReferences(Map<String, String> transversalMap, Site site) {
        if (transversalMap != null && !transversalMap.isEmpty() && site != null) {
            Set<Map.Entry<String, String>> entrySet = transversalMap.entrySet();

            String msgBody = site.getDescription();
            if (StringUtils.isNotBlank(msgBody)) {
                String msgBodyPreMigrate = msgBody;
                msgBody = linkMigrationHelper.migrateAllLinks(entrySet, msgBody);

                if (!msgBody.equals(msgBodyPreMigrate)) {
                    //update the site b/c some tools (Lessonbuilder) updates the site structure (add/remove pages) and we don't want to
                    //over write this
                    try {
                        site = siteService.getSite(site.getId());
                        site.setDescription(msgBody);
                        saveSite(site);
                    } catch (IdUnusedException iue) {
                        log.warn("A site with id {} doesn't exist, {}", site.getId(), iue.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Copy tool permissions from source site to destination site
     * @param fromSiteId The source site ID
     * @param toSiteId The destination site ID
     */
    private void copyToolPermissions(String fromSiteId, String toSiteId) {
        try {
            // Get the source site
            log.debug("Starting tool permissions copy from site {} to site {}", fromSiteId, toSiteId);
            Site fromSite = siteService.getSite(fromSiteId);
            // Get the destination site
            Site toSite = siteService.getSite(toSiteId);
            
            // Copy all role permissions from source site to destination site
            Set<Role> fromRoles = fromSite.getRoles();
            log.debug("Found {} roles in source site", fromRoles.size());
            
            for (Role fromRole : fromRoles) {
                String roleName = fromRole.getId();
                try {
                    // Get the corresponding role in the destination site
                    Role toRole = toSite.getRole(roleName);
                    if (toRole != null) {
                        // Get all permissions from the source role
                        Set<String> allPermissions = fromRole.getAllowedFunctions();
                        log.debug("Copying {} permissions for role {}", allPermissions.size(), roleName);
                        
                        // Copy all permissions (don't filter by tool)
                        for (String permission : allPermissions) {
                            // If the source role has this permission, add it to the destination role
                            if (fromRole.isAllowed(permission)) {
                                toRole.allowFunction(permission);
                                log.debug("Allowing permission {} for role {}", permission, roleName);
                            } else {
                                // If the permission was explicitly disallowed, disallow it in the destination role
                                toRole.disallowFunction(permission);
                                log.debug("Disallowing permission {} for role {}", permission, roleName);
                            }
                        }
                    } else {
                        log.warn("Role {} not found in destination site {}", roleName, toSiteId);
                    }
                } catch (Exception e) {
                    log.warn("Could not find role {} in destination site {}, {}", roleName, toSiteId, e.toString());
                }
            }
            
            // Save the destination site with the updated permissions
            siteService.save(toSite);
            log.debug("Successfully copied all tool permissions from site {} to site {}", fromSiteId, toSiteId);
            
        } catch (IdUnusedException e) {
            log.warn("Could not find site when copying permissions: {}", e.toString());
        } catch (Exception e) {
            log.error("Could not copy tool permissions from site {} to site {}", fromSiteId, toSiteId, e.toString());
        }
    }
}
