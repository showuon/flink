/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.rest.handler.job;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.rest.handler.HandlerRequest;
import org.apache.flink.runtime.rest.handler.legacy.ExecutionGraphCache;
import org.apache.flink.runtime.rest.messages.EmptyRequestBody;
import org.apache.flink.runtime.rest.messages.JobExceptionsInfoWithHistory;
import org.apache.flink.runtime.rest.messages.JobIDPathParameter;
import org.apache.flink.runtime.rest.messages.MessageHeaders;
import org.apache.flink.runtime.rest.messages.ResponseBody;
import org.apache.flink.runtime.rest.messages.job.FailureLabelFilterParameter;
import org.apache.flink.runtime.rest.messages.job.JobExceptionsMessageParameters;
import org.apache.flink.runtime.rest.messages.job.UpperLimitExceptionParameter;
import org.apache.flink.runtime.scheduler.ExecutionGraphInfo;
import org.apache.flink.runtime.scheduler.exceptionhistory.ExceptionHistoryEntry;
import org.apache.flink.runtime.scheduler.exceptionhistory.RootExceptionHistoryEntry;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;
import org.apache.flink.runtime.webmonitor.RestfulGateway;
import org.apache.flink.runtime.webmonitor.history.ArchivedJson;
import org.apache.flink.runtime.webmonitor.history.JsonArchivist;
import org.apache.flink.runtime.webmonitor.retriever.GatewayRetriever;
import org.apache.flink.util.Preconditions;

import org.apache.flink.shaded.curator5.com.google.common.collect.Iterables;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** Handler serving the job exceptions. */
public class JobExceptionsHandler
        extends AbstractExecutionGraphHandler<
                JobExceptionsInfoWithHistory, JobExceptionsMessageParameters>
        implements JsonArchivist {

    static final int MAX_NUMBER_EXCEPTION_TO_REPORT = 20;
    static final List<FailureLabelFilterParameter.FailureLabel> EMPTY_FAILURE_LABEL_FILTER =
            Collections.emptyList();

    public JobExceptionsHandler(
            GatewayRetriever<? extends RestfulGateway> leaderRetriever,
            Time timeout,
            Map<String, String> responseHeaders,
            MessageHeaders<
                            EmptyRequestBody,
                            JobExceptionsInfoWithHistory,
                            JobExceptionsMessageParameters>
                    messageHeaders,
            ExecutionGraphCache executionGraphCache,
            Executor executor) {

        super(
                leaderRetriever,
                timeout,
                responseHeaders,
                messageHeaders,
                executionGraphCache,
                executor);
    }

    @Override
    protected JobExceptionsInfoWithHistory handleRequest(
            HandlerRequest<EmptyRequestBody> request, ExecutionGraphInfo executionGraph) {
        final List<Integer> exceptionToReportMaxSizes =
                request.getQueryParameter(UpperLimitExceptionParameter.class);
        final int exceptionToReportMaxSize =
                exceptionToReportMaxSizes.size() > 0
                        ? exceptionToReportMaxSizes.get(0)
                        : MAX_NUMBER_EXCEPTION_TO_REPORT;
        List<FailureLabelFilterParameter.FailureLabel> failureLabelFilter =
                request.getQueryParameter(FailureLabelFilterParameter.class);
        failureLabelFilter =
                failureLabelFilter.size() > 0 ? failureLabelFilter : EMPTY_FAILURE_LABEL_FILTER;
        return createJobExceptionsInfo(
                executionGraph, exceptionToReportMaxSize, failureLabelFilter);
    }

    @Override
    public Collection<ArchivedJson> archiveJsonWithPath(ExecutionGraphInfo executionGraphInfo)
            throws IOException {
        ResponseBody json =
                createJobExceptionsInfo(
                        executionGraphInfo,
                        MAX_NUMBER_EXCEPTION_TO_REPORT,
                        EMPTY_FAILURE_LABEL_FILTER);
        String path =
                getMessageHeaders()
                        .getTargetRestEndpointURL()
                        .replace(
                                ':' + JobIDPathParameter.KEY,
                                executionGraphInfo.getJobId().toString());
        return Collections.singletonList(new ArchivedJson(path, json));
    }

    private static JobExceptionsInfoWithHistory createJobExceptionsInfo(
            ExecutionGraphInfo executionGraphInfo,
            int exceptionToReportMaxSize,
            List<FailureLabelFilterParameter.FailureLabel> failureLabelFilter) {
        return new JobExceptionsInfoWithHistory(
                createJobExceptionHistory(
                        executionGraphInfo.getExceptionHistory(),
                        exceptionToReportMaxSize,
                        failureLabelFilter));
    }

    private static JobExceptionsInfoWithHistory.JobExceptionHistory createJobExceptionHistory(
            Iterable<RootExceptionHistoryEntry> historyEntries,
            int limit,
            List<FailureLabelFilterParameter.FailureLabel> failureLabelFilter) {
        // we need to reverse the history to have a stable result when doing paging on it
        List<RootExceptionHistoryEntry> reversedHistoryEntries = new ArrayList<>();
        Iterables.addAll(reversedHistoryEntries, historyEntries);
        Collections.reverse(reversedHistoryEntries);

        if (!failureLabelFilter.isEmpty()) {
            reversedHistoryEntries =
                    reversedHistoryEntries.stream()
                            .filter(
                                    entry -> {
                                        for (FailureLabelFilterParameter.FailureLabel label :
                                                failureLabelFilter) {
                                            if (!entry.getFailureLabels()
                                                            .containsKey(label.getKey())
                                                    || !entry.getFailureLabels()
                                                            .get(label.getKey())
                                                            .equals(label.getValue())) {
                                                return false;
                                            }
                                        }
                                        return true;
                                    })
                            .collect(Collectors.toList());
        }

        final List<JobExceptionsInfoWithHistory.RootExceptionInfo> exceptionHistoryEntries =
                reversedHistoryEntries.stream()
                        .limit(limit)
                        .map(JobExceptionsHandler::createRootExceptionInfo)
                        .collect(Collectors.toList());

        return new JobExceptionsInfoWithHistory.JobExceptionHistory(
                exceptionHistoryEntries,
                exceptionHistoryEntries.size() < reversedHistoryEntries.size());
    }

    private static JobExceptionsInfoWithHistory.RootExceptionInfo createRootExceptionInfo(
            RootExceptionHistoryEntry historyEntry) {
        final List<JobExceptionsInfoWithHistory.ExceptionInfo> concurrentExceptions =
                StreamSupport.stream(historyEntry.getConcurrentExceptions().spliterator(), false)
                        .map(JobExceptionsHandler::createExceptionInfo)
                        .collect(Collectors.toList());

        if (historyEntry.isGlobal()) {
            return new JobExceptionsInfoWithHistory.RootExceptionInfo(
                    historyEntry.getException().getOriginalErrorClassName(),
                    historyEntry.getExceptionAsString(),
                    historyEntry.getTimestamp(),
                    historyEntry.getFailureLabels(),
                    concurrentExceptions);
        }

        assertLocalExceptionInfo(historyEntry);

        return new JobExceptionsInfoWithHistory.RootExceptionInfo(
                historyEntry.getException().getOriginalErrorClassName(),
                historyEntry.getExceptionAsString(),
                historyEntry.getTimestamp(),
                historyEntry.getFailureLabels(),
                historyEntry.getFailingTaskName(),
                toString(historyEntry.getTaskManagerLocation()),
                toTaskManagerId(historyEntry.getTaskManagerLocation()),
                concurrentExceptions);
    }

    private static JobExceptionsInfoWithHistory.ExceptionInfo createExceptionInfo(
            ExceptionHistoryEntry exceptionHistoryEntry) {

        if (exceptionHistoryEntry.isGlobal()) {
            return new JobExceptionsInfoWithHistory.ExceptionInfo(
                    exceptionHistoryEntry.getException().getOriginalErrorClassName(),
                    exceptionHistoryEntry.getExceptionAsString(),
                    exceptionHistoryEntry.getTimestamp(),
                    exceptionHistoryEntry.getFailureLabels(),
                    null,
                    null,
                    null);
        }

        assertLocalExceptionInfo(exceptionHistoryEntry);

        return new JobExceptionsInfoWithHistory.ExceptionInfo(
                exceptionHistoryEntry.getException().getOriginalErrorClassName(),
                exceptionHistoryEntry.getExceptionAsString(),
                exceptionHistoryEntry.getTimestamp(),
                exceptionHistoryEntry.getFailureLabels(),
                exceptionHistoryEntry.getFailingTaskName(),
                toString(exceptionHistoryEntry.getTaskManagerLocation()),
                toTaskManagerId(exceptionHistoryEntry.getTaskManagerLocation()));
    }

    private static void assertLocalExceptionInfo(ExceptionHistoryEntry exceptionHistoryEntry) {
        Preconditions.checkArgument(
                exceptionHistoryEntry.getFailingTaskName() != null,
                "The taskName must not be null for a non-global failure.");
    }

    @VisibleForTesting
    static String toString(@Nullable TaskManagerLocation location) {
        // '(unassigned)' being the default value is added to support backward-compatibility for the
        // deprecated fields
        return location != null ? location.getEndpoint() : "(unassigned)";
    }

    @VisibleForTesting
    static String toTaskManagerId(@Nullable TaskManagerLocation location) {
        // '(unassigned)' being the default value is added to support backward-compatibility for the
        // deprecated fields
        return location != null ? String.format("%s", location.getResourceID()) : "(unassigned)";
    }

    @VisibleForTesting
    @Nullable
    static String toString(@Nullable ExceptionHistoryEntry.ArchivedTaskManagerLocation location) {
        return location != null ? location.getEndpoint() : null;
    }

    @VisibleForTesting
    static String toTaskManagerId(
            @Nullable ExceptionHistoryEntry.ArchivedTaskManagerLocation location) {
        return location != null ? String.format("%s", location.getResourceID()) : null;
    }
}
