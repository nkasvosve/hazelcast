package com.hazelcast.internal.query.worker.control;

import com.hazelcast.cluster.Member;
import com.hazelcast.internal.query.QueryFragment;
import com.hazelcast.internal.query.QueryId;
import com.hazelcast.internal.query.exec.EmptyScanExec;
import com.hazelcast.internal.query.exec.Exec;
import com.hazelcast.internal.query.exec.MapScanExec;
import com.hazelcast.internal.query.exec.ReceiveExec;
import com.hazelcast.internal.query.exec.RootExec;
import com.hazelcast.internal.query.exec.SendExec;
import com.hazelcast.internal.query.exec.SortExec;
import com.hazelcast.internal.query.exec.SortMergeReceiveExec;
import com.hazelcast.internal.query.mailbox.Inbox;
import com.hazelcast.internal.query.mailbox.Outbox;
import com.hazelcast.internal.query.mailbox.SingleInbox;
import com.hazelcast.internal.query.mailbox.StripedInbox;
import com.hazelcast.internal.query.physical.MapScanPhysicalNode;
import com.hazelcast.internal.query.physical.PhysicalNodeVisitor;
import com.hazelcast.internal.query.physical.ReceivePhysicalNode;
import com.hazelcast.internal.query.physical.RootPhysicalNode;
import com.hazelcast.internal.query.physical.SendPhysicalNode;
import com.hazelcast.internal.query.physical.SortMergeReceivePhysicalNode;
import com.hazelcast.internal.query.physical.SortPhysicalNode;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.util.collection.PartitionIdSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Visitor which builds an executor for every observed node.
 */
public class ExecutorCreatePhysicalNodeVisitor implements PhysicalNodeVisitor {
    /** Node engine. */
    private final NodeEngine nodeEngine;

    /** Query ID. */
    private final QueryId queryId;

    /** Number of data partitions. */
    private final int partCnt;

    /** Partitions owned by this data node. */
    private final PartitionIdSet localParts;

    /** Map from receive (inbound) edge to it's count. */
    private final Map<Integer, QueryFragment> sendFragmentMap;

    /** Map from receive (inbound) edge to it's count. */
    private final Map<Integer, QueryFragment> receiveFragmentMap;

    /** Stripe index. */
    private int stripe;

    /** Number of stripes. */
    private int stripeCnt;

    /** Participating nodes. */
    private List<Member> members;

    /** Stack of elements to be merged. */
    private final ArrayList<Exec> stack = new ArrayList<>(1);

    /** Result. */
    private Exec exec;

    private List<Inbox> inboxes = new ArrayList<>();
    private List<Outbox> outboxes = new ArrayList<>();

    public ExecutorCreatePhysicalNodeVisitor(
        NodeEngine nodeEngine,
        QueryId queryId,
        int partCnt,
        PartitionIdSet localParts,
        Map<Integer, QueryFragment> sendFragmentMap,
        Map<Integer, QueryFragment> receiveFragmentMap
    ) {
        this.nodeEngine = nodeEngine;
        this.queryId = queryId;
        this.partCnt = partCnt;
        this.localParts = localParts;
        this.sendFragmentMap = sendFragmentMap;
        this.receiveFragmentMap = receiveFragmentMap;
    }

    @Override
    public void onRootNode(RootPhysicalNode node) {
        assert stack.size() == 1;

        exec = new RootExec(stack.get(0));
    }

    @Override
    public void onReceiveNode(ReceivePhysicalNode node) {
        // Navigate to sender exec and calculate total number of sender stripes.
        int edgeId = node.getEdgeId();

        QueryFragment sendFragment = sendFragmentMap.get(edgeId);

        int remaining = sendFragment.getMemberIds().size() * sendFragment.getParallelism();

        // TODO: Inbox type should depend on sender-receiver edge.
        // Create and register inbox.
        SingleInbox inbox = new SingleInbox(
            queryId,
            node.getEdgeId(),
            stripe,
            remaining
        );

        inboxes.add(inbox);

        // Instantiate executor and put it to stack.
        ReceiveExec res = new ReceiveExec(inbox);

        stack.add(res);
    }

    @Override
    public void onSortMergeReceiveNode(SortMergeReceivePhysicalNode node) {
        // Navigate to sender exec and calculate total number of sender stripes.
        int edgeId = node.getEdgeId();

        // Create and register inbox.
        QueryFragment sendFragment = sendFragmentMap.get(edgeId);

        StripedInbox inbox = new StripedInbox(
            queryId,
            edgeId,
            stripe,
            sendFragment.getMemberIds(),
            sendFragment.getParallelism()
        );

        inboxes.add(inbox);

        // Instantiate executor and put it to stack.
        SortMergeReceiveExec res = new SortMergeReceiveExec(
            inbox,
            node.getExpressions(),
            node.getAscs()
        );

        stack.add(res);
    }

    @Override
    public void onSendNode(SendPhysicalNode node) {
        assert stack.size() == 1;

        Outbox[] sendOutboxes;

        if (node.isUseDataPartitions()) {
            // Partition by data partitions.
            // TODO: Implement for joins.
            sendOutboxes = null;
        }
        else {
            // Partition by member count * parallelism.
            QueryFragment receiveFragment = receiveFragmentMap.get(node.getEdgeId());

            int partCnt = receiveFragment.getMemberIds().size() * receiveFragment.getParallelism();

            sendOutboxes = new Outbox[partCnt];

            int idx = 0;

            for (String receiveMemberId : receiveFragment.getMemberIds()) {
                // TODO: Race! Get all members in advance!
                Member receiveMember = nodeEngine.getClusterService().getMember(receiveMemberId);

                for (int j = 0; j < receiveFragment.getParallelism(); j++) {
                    Outbox outbox = new Outbox(
                        node.getEdgeId(),
                        stripe,
                        queryId,
                        nodeEngine,
                        receiveMember,
                        1024, // TODO: Configurable batching.
                        j
                    );

                    sendOutboxes[idx++] = outbox;

                    outboxes.add(outbox);
                }
            }
        }

        exec = new SendExec(stack.get(0), node.getEdgeId(), node.getPartitionHasher(), sendOutboxes);
    }

    @Override
    public void onMapScanNode(MapScanPhysicalNode node) {
        PartitionIdSet stripeParts = new PartitionIdSet(partCnt);

        int stripePartsCnt = 0;

        if (localParts != null) {
            int ctr = 0;

            for (int i = 0; i < partCnt; i++) {
                if (localParts.contains(i)) {
                    if (ctr++ % stripeCnt == stripe) {
                        stripeParts.add(i);
                        stripePartsCnt++;
                    }
                }
            }
        }

        Exec res;

        if (stripePartsCnt == 0)
            res = EmptyScanExec.INSTANCE;
        else
            res = new MapScanExec(node.getMapName(), stripeParts, node.getProjections(), node.getFilter());

        stack.add(res);
    }

    @Override
    public void onSortNode(SortPhysicalNode node) {
        Exec res = new SortExec(stack.remove(0), node.getExpressions(), node.getAscs());

        stack.add(res);
    }

    public Exec getExec() {
        return exec;
    }

    public List<Inbox> getInboxes() {
        return inboxes != null ? inboxes : Collections.emptyList();
    }

    public List<Outbox> getOutboxes() {
        return outboxes != null ? outboxes : Collections.emptyList();
    }

    /**
     * Reset visitor state.
     *
     * @param stripe New stripe.
     * @param stripeCnt Total number of stripes.
     */
    public void reset(int stripe, int stripeCnt, List<Member> members) {
        assert stack.isEmpty();

        exec = null;
        inboxes = new ArrayList<>();
        outboxes = new ArrayList<>();

        this.stripe = stripe;
        this.stripeCnt = stripeCnt;
        this.members = members;
    }
}
