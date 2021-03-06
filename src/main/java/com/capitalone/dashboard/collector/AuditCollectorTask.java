package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.client.RestClient;
import com.capitalone.dashboard.model.Audit;
import com.capitalone.dashboard.model.AuditResult;
import com.capitalone.dashboard.model.AuditType;
import com.capitalone.dashboard.model.Cmdb;
import com.capitalone.dashboard.model.Dashboard;
import com.capitalone.dashboard.model.DashboardType;
import com.capitalone.dashboard.repository.AuditCollectorRepository;
import com.capitalone.dashboard.repository.AuditResultRepository;
import com.capitalone.dashboard.repository.BaseCollectorRepository;
import com.capitalone.dashboard.repository.CmdbRepository;
import com.capitalone.dashboard.repository.CollectorItemRepository;
import com.capitalone.dashboard.repository.ComponentRepository;
import com.capitalone.dashboard.repository.DashboardRepository;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * <h1>AuditCollectorTask</h1>
 * This task finds recent dashboards, collect and store audit statuses
 *
 * @since 09/28/2018
 */
@Component
public class AuditCollectorTask extends CollectorTask<AuditCollector> {

    private final Logger LOGGER = LoggerFactory.getLogger(AuditCollectorTask.class);
    private DashboardRepository dashboardRepository;
    private AuditResultRepository auditResultRepository;
    private AuditCollectorRepository auditCollectorRepository;
    private AuditSettings settings;
    private CmdbRepository cmdbRepository;
    private ComponentRepository componentRepository;
    private CollectorItemRepository collectorItemRepository;
    private static final String COLLECTOR_NAME = "AuditCollector";
    private RestClient restClient;

    @Autowired
    public AuditCollectorTask(TaskScheduler taskScheduler, DashboardRepository dashboardRepository,
                              AuditResultRepository auditResultRepository, AuditCollectorRepository auditCollectorRepository,
                              CmdbRepository cmdbRepository, ComponentRepository componentRepository,
                              CollectorItemRepository collectorItemRepository, AuditSettings settings,
                              RestClient restClient) {
        super(taskScheduler, COLLECTOR_NAME);
        this.dashboardRepository = dashboardRepository;
        this.auditResultRepository = auditResultRepository;
        this.auditCollectorRepository = auditCollectorRepository;
        this.cmdbRepository = cmdbRepository;
        this.componentRepository = componentRepository;
        this.collectorItemRepository = collectorItemRepository;
        this.settings = settings;
        this.restClient = restClient;
    }

    @Override
    public void collect(AuditCollector collector) {
        long startTime = System.currentTimeMillis();
        LOGGER.info(String.format("NFRRCollectorTask:collect start collector_run_id=%d", startTime));
        LOGGER.info("NFRR Audit Collector pulls all the team dashboards");

        Iterable<Dashboard> dashboards = dashboardRepository.findAllByType(DashboardType.Team);
        collectAuditResults(dashboards, startTime);
        long endTime = System.currentTimeMillis();
        long elapsedSeconds = (endTime-startTime)/1000;
        collector.setLastExecutedSeconds(elapsedSeconds);
        collector.setLastExecutionRecordCount(CollectionUtils.size(dashboards));
        LOGGER.info("NFRR Audit Collector executed successfully");
        LOGGER.info(String.format("NFRRCollectorTask:collect stop, collector_process_time=%d collector_item_count=%d collector_run_id=%d", elapsedSeconds, CollectionUtils.size(dashboards), startTime));
        }

    /**
     * Collect audit results of dashboards
     *
     * @param dashboards
     */
    protected void collectAuditResults(Iterable<Dashboard> dashboards, long collector_run_id) {
        int numberOfAuditDays = settings.getDays();
        long auditBeginDateTimeStamp = Instant.now().minus(Duration.ofDays(numberOfAuditDays)).toEpochMilli();
        long auditEndDateTimeStamp = Instant.now().toEpochMilli();
        int totTeamDbdCount = CollectionUtils.size(dashboards);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd:HH:mm:ss");
        LOGGER.info(String.format("Audit time range time_range_start=%d (%s) time_range_end=%d (%s) dashboard_count=%d collector_run_id=%d",
                auditBeginDateTimeStamp, sdf.format(new Date(auditBeginDateTimeStamp)),
                auditEndDateTimeStamp, sdf.format(new Date(auditEndDateTimeStamp)), totTeamDbdCount, collector_run_id));

        AuditCollector collector = getCollectorRepository().findByName(COLLECTOR_NAME);
        AuditCollectorUtil auditCollectorUtil = new AuditCollectorUtil(collector, componentRepository,
                collectorItemRepository, restClient);
        int index = 0;
        for (Dashboard dashboard : dashboards) {
            long startTime = System.currentTimeMillis();
            try {
                Map<AuditType, Audit> auditMap = auditCollectorUtil.getAudit(dashboard, settings,
                        auditBeginDateTimeStamp, auditEndDateTimeStamp);
                Cmdb cmdb = cmdbRepository.findByConfigurationItem(dashboard.getConfigurationItemBusServName());
                List<AuditResult> latestAuditResults = AuditCollectorUtil.getAuditResults(dashboard, auditMap, cmdb, auditEndDateTimeStamp);
                refreshAuditResults(dashboard, latestAuditResults);
            } catch (Exception e) {
                LOGGER.error("Exception in collecting audit result for error_dashboard=" + dashboard.getTitle() +" collector_run_id=" + collector_run_id , e);
            }
            long endTime = System.currentTimeMillis();
            LOGGER.info(String.format("Adding audit results - dashboard=%s [%d/%d] duration=%d collector_run_id=%d",
                    dashboard.getTitle(), ++index, totTeamDbdCount, endTime-startTime, collector_run_id));
        }
    }

    /**
     * Refresh the audit results
     * @param dashboard
     * @param latestAuditResults
     */
    private void refreshAuditResults(Dashboard dashboard, List<AuditResult> latestAuditResults) {
        if (CollectionUtils.isNotEmpty(latestAuditResults)) {
            Iterable<AuditResult> oldAuditResults = auditResultRepository.findByDashboardTitle(dashboard.getTitle());
            auditResultRepository.delete(oldAuditResults);
            auditResultRepository.save(latestAuditResults);
        }
    }

    @Override
    public AuditCollector getCollector() {
        return AuditCollector.prototype(this.settings.getServers());
    }

    @Override
    public BaseCollectorRepository<AuditCollector> getCollectorRepository() {
        return auditCollectorRepository;
    }

    /**
     * This property helps to determine AuditStatus Collector execution interval
     */
    @Override
    public String getCron() {
        return this.settings.getCron();
    }

}
