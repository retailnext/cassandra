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
package org.apache.cassandra.cql3.statements;

import java.nio.ByteBuffer;
import java.util.*;

import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;

import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.cql3.*;
import org.apache.cassandra.transport.messages.ResultMessage;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.*;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.dht.*;
import org.apache.cassandra.exceptions.*;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.service.pager.*;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.thrift.ThriftValidation;
import org.apache.cassandra.serializers.MarshalException;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;

/**
 * Encapsulates a completely parsed SELECT query, including the target
 * column family, expression, result count, and ordering clause.
 *
 */
public class SelectStatement implements CQLStatement
{
    private static final int DEFAULT_COUNT_PAGE_SIZE = 10000;

    private final int boundTerms;
    public final CFMetaData cfm;
    public final Parameters parameters;
    private final Selection selection;
    private final Term limit;

    private final Restriction[] keyRestrictions;
    private final Restriction[] columnRestrictions;
    private final Map<ColumnIdentifier, Restriction> metadataRestrictions = new HashMap<ColumnIdentifier, Restriction>();

    // All restricted columns not covered by the key or index filter
    private final Set<ColumnDefinition> restrictedColumns = new HashSet<ColumnDefinition>();
    private Restriction.Slice sliceRestriction;

    private boolean isReversed;
    private boolean onToken;
    private boolean isKeyRange;
    private boolean keyIsInRelation;
    private boolean usesSecondaryIndexing;

    private Map<ColumnDefinition, Integer> orderingIndexes;

    // Used by forSelection below
    private static final Parameters defaultParameters = new Parameters(Collections.<ColumnIdentifier, Boolean>emptyMap(), false, false, null, false);

    public SelectStatement(CFMetaData cfm, int boundTerms, Parameters parameters, Selection selection, Term limit)
    {
        this.cfm = cfm;
        this.boundTerms = boundTerms;
        this.selection = selection;
        this.keyRestrictions = new Restriction[cfm.partitionKeyColumns().size()];
        this.columnRestrictions = new Restriction[cfm.clusteringColumns().size()];
        this.parameters = parameters;
        this.limit = limit;
    }

    // Creates a simple select based on the given selection.
    // Note that the results select statement should not be used for actual queries, but only for processing already
    // queried data through processColumnFamily.
    static SelectStatement forSelection(CFMetaData cfm, Selection selection)
    {
        return new SelectStatement(cfm, 0, defaultParameters, selection, null);
    }

    public ResultSet.Metadata getResultMetadata()
    {
        return parameters.isCount
             ? ResultSet.makeCountMetadata(keyspace(), columnFamily(), parameters.countAlias)
             : selection.getResultMetadata();
    }

    public int getBoundsTerms()
    {
        return boundTerms;
    }

    public void checkAccess(ClientState state) throws InvalidRequestException, UnauthorizedException
    {
        state.hasColumnFamilyAccess(keyspace(), columnFamily(), Permission.SELECT);
    }

    public void validate(ClientState state) throws InvalidRequestException
    {
        // Nothing to do, all validation has been done by RawStatement.prepare()
    }

    public ResultMessage.Rows execute(QueryState state, QueryOptions options) throws RequestExecutionException, RequestValidationException
    {
        ConsistencyLevel cl = options.getConsistency();
        List<ByteBuffer> variables = options.getValues();
        if (cl == null)
            throw new InvalidRequestException("Invalid empty consistency level");

        cl.validateForRead(keyspace());

        int limit = getLimit(variables);
        long now = System.currentTimeMillis();
        Pageable command;
        if (isKeyRange || usesSecondaryIndexing)
        {
            command = getRangeCommand(variables, limit, now);
        }
        else
        {
            List<ReadCommand> commands = getSliceCommands(variables, limit, now);
            command = commands == null ? null : new Pageable.ReadCommands(commands);
        }

        int pageSize = options.getPageSize();
        // A count query will never be paged for the user, but we always page it internally to avoid OOM.
        // If we user provided a pageSize we'll use that to page internally (because why not), otherwise we use our default
        if (parameters.isCount && pageSize <= 0)
            pageSize = DEFAULT_COUNT_PAGE_SIZE;

        if (pageSize <= 0 || command == null || !QueryPagers.mayNeedPaging(command, pageSize))
        {
            return execute(command, cl, variables, limit, now);
        }
        else
        {
            QueryPager pager = QueryPagers.pager(command, cl, options.getPagingState());
            if (parameters.isCount)
                return pageCountQuery(pager, variables, pageSize, now);

            List<Row> page = pager.fetchPage(pageSize);
            ResultMessage.Rows msg = processResults(page, variables, limit, now);
            if (!pager.isExhausted())
                msg.result.metadata.setHasMorePages(pager.state());
            return msg;
        }
    }

    private ResultMessage.Rows execute(Pageable command, ConsistencyLevel cl, List<ByteBuffer> variables, int limit, long now) throws RequestValidationException, RequestExecutionException
    {
        List<Row> rows;
        if (command == null)
        {
            rows = Collections.<Row>emptyList();
        }
        else
        {
            rows = command instanceof Pageable.ReadCommands
                 ? StorageProxy.read(((Pageable.ReadCommands)command).commands, cl)
                 : StorageProxy.getRangeSlice((RangeSliceCommand)command, cl);
        }

        return processResults(rows, variables, limit, now);
    }

    private ResultMessage.Rows pageCountQuery(QueryPager pager, List<ByteBuffer> variables, int pageSize, long now) throws RequestValidationException, RequestExecutionException
    {
        int count = 0;
        while (!pager.isExhausted())
        {
            int maxLimit = pager.maxRemaining();
            ResultSet rset = process(pager.fetchPage(pageSize), variables, maxLimit, now);
            count += rset.rows.size();
        }

        ResultSet result = ResultSet.makeCountResult(keyspace(), columnFamily(), count, parameters.countAlias);
        return new ResultMessage.Rows(result);
    }

    public ResultMessage.Rows processResults(List<Row> rows, List<ByteBuffer> variables, int limit, long now) throws RequestValidationException
    {
        // Even for count, we need to process the result as it'll group some column together in sparse column families
        ResultSet rset = process(rows, variables, limit, now);
        rset = parameters.isCount ? rset.makeCountResult(parameters.countAlias) : rset;
        return new ResultMessage.Rows(rset);
    }

    static List<Row> readLocally(String keyspaceName, List<ReadCommand> cmds)
    {
        Keyspace keyspace = Keyspace.open(keyspaceName);
        List<Row> rows = new ArrayList<Row>(cmds.size());
        for (ReadCommand cmd : cmds)
            rows.add(cmd.getRow(keyspace));
        return rows;
    }

    public ResultMessage.Rows executeInternal(QueryState state) throws RequestExecutionException, RequestValidationException
    {
        List<ByteBuffer> variables = Collections.emptyList();
        int limit = getLimit(variables);
        long now = System.currentTimeMillis();
        List<Row> rows;
        if (isKeyRange || usesSecondaryIndexing)
        {
            RangeSliceCommand command = getRangeCommand(variables, limit, now);
            rows = command == null ? Collections.<Row>emptyList() : command.executeLocally();
        }
        else
        {
            List<ReadCommand> commands = getSliceCommands(variables, limit, now);
            rows = commands == null ? Collections.<Row>emptyList() : readLocally(keyspace(), commands);
        }

        return processResults(rows, variables, limit, now);
    }

    public ResultSet process(List<Row> rows) throws InvalidRequestException
    {
        assert !parameters.isCount; // not yet needed
        return process(rows, Collections.<ByteBuffer>emptyList(), getLimit(Collections.<ByteBuffer>emptyList()), System.currentTimeMillis());
    }

    public String keyspace()
    {
        return cfm.ksName;
    }

    public String columnFamily()
    {
        return cfm.cfName;
    }

    private List<ReadCommand> getSliceCommands(List<ByteBuffer> variables, int limit, long now) throws RequestValidationException
    {
        Collection<ByteBuffer> keys = getKeys(variables);
        if (keys.isEmpty()) // in case of IN () for (the last column of) the partition key.
            return null;

        List<ReadCommand> commands = new ArrayList<ReadCommand>(keys.size());

        IDiskAtomFilter filter = makeFilter(variables, limit);
        if (filter == null)
            return null;

        // Note that we use the total limit for every key, which is potentially inefficient.
        // However, IN + LIMIT is not a very sensible choice.
        for (ByteBuffer key : keys)
        {
            QueryProcessor.validateKey(key);
            // We should not share the slice filter amongst the commands (hence the cloneShallow), due to
            // SliceQueryFilter not being immutable due to its columnCounter used by the lastCounted() method
            // (this is fairly ugly and we should change that but that's probably not a tiny refactor to do that cleanly)
            commands.add(ReadCommand.create(keyspace(), key, columnFamily(), now, filter.cloneShallow()));
        }

        return commands;
    }

    private RangeSliceCommand getRangeCommand(List<ByteBuffer> variables, int limit, long now) throws RequestValidationException
    {
        IDiskAtomFilter filter = makeFilter(variables, limit);
        if (filter == null)
            return null;

        List<IndexExpression> expressions = getIndexExpressions(variables);
        // The LIMIT provided by the user is the number of CQL row he wants returned.
        // We want to have getRangeSlice to count the number of columns, not the number of keys.
        AbstractBounds<RowPosition> keyBounds = getKeyBounds(variables);
        return keyBounds == null
             ? null
             : new RangeSliceCommand(keyspace(), columnFamily(), now,  filter, keyBounds, expressions, limit, !parameters.isDistinct, false);
    }

    private AbstractBounds<RowPosition> getKeyBounds(List<ByteBuffer> variables) throws InvalidRequestException
    {
        IPartitioner<?> p = StorageService.getPartitioner();

        if (onToken)
        {
            Token startToken = getTokenBound(Bound.START, variables, p);
            Token endToken = getTokenBound(Bound.END, variables, p);

            boolean includeStart = includeKeyBound(Bound.START);
            boolean includeEnd = includeKeyBound(Bound.END);

            /*
             * If we ask SP.getRangeSlice() for (token(200), token(200)], it will happily return the whole ring.
             * However, wrapping range doesn't really make sense for CQL, and we want to return an empty result
             * in that case (CASSANDRA-5573). So special case to create a range that is guaranteed to be empty.
             *
             * In practice, we want to return an empty result set if either startToken > endToken, or both are
             * equal but one of the bound is excluded (since [a, a] can contains something, but not (a, a], [a, a)
             * or (a, a)). Note though that in the case where startToken or endToken is the minimum token, then
             * this special case rule should not apply.
             */
            int cmp = startToken.compareTo(endToken);
            if (!startToken.isMinimum() && !endToken.isMinimum() && (cmp > 0 || (cmp == 0 && (!includeStart || !includeEnd))))
                return null;

            RowPosition start = includeStart ? startToken.minKeyBound() : startToken.maxKeyBound();
            RowPosition end = includeEnd ? endToken.maxKeyBound() : endToken.minKeyBound();

            return new Range<RowPosition>(start, end);
        }
        else
        {
            ByteBuffer startKeyBytes = getKeyBound(Bound.START, variables);
            ByteBuffer finishKeyBytes = getKeyBound(Bound.END, variables);

            RowPosition startKey = RowPosition.forKey(startKeyBytes, p);
            RowPosition finishKey = RowPosition.forKey(finishKeyBytes, p);

            if (startKey.compareTo(finishKey) > 0 && !finishKey.isMinimum(p))
                return null;

            if (includeKeyBound(Bound.START))
            {
                return includeKeyBound(Bound.END)
                     ? new Bounds<RowPosition>(startKey, finishKey)
                     : new IncludingExcludingBounds<RowPosition>(startKey, finishKey);
            }
            else
            {
                return includeKeyBound(Bound.END)
                     ? new Range<RowPosition>(startKey, finishKey)
                     : new ExcludingBounds<RowPosition>(startKey, finishKey);
            }
        }
    }

    private IDiskAtomFilter makeFilter(List<ByteBuffer> variables, int limit)
    throws InvalidRequestException
    {
        if (parameters.isDistinct)
        {
            return new SliceQueryFilter(ColumnSlice.ALL_COLUMNS_ARRAY, false, 1, -1);
        }
        else if (isColumnRange())
        {
            // For sparse, we used to ask for 'defined columns' * 'asked limit' (where defined columns includes the row marker)
            // to account for the grouping of columns.
            // Since that doesn't work for maps/sets/lists, we now use the compositesToGroup option of SliceQueryFilter.
            // But we must preserve backward compatibility too (for mixed version cluster that is).
            int toGroup = cfm.isDense() ? -1 : cfm.clusteringColumns().size();
            List<ByteBuffer> startBounds = getRequestedBound(Bound.START, variables);
            List<ByteBuffer> endBounds = getRequestedBound(Bound.END, variables);
            assert startBounds.size() == endBounds.size();

            // The case where startBounds == 1 is common enough that it's worth optimizing
            ColumnSlice[] slices;
            if (startBounds.size() == 1)
            {
                ColumnSlice slice = new ColumnSlice(startBounds.get(0), endBounds.get(0));
                if (slice.isAlwaysEmpty(cfm.comparator, isReversed))
                    return null;
                slices = new ColumnSlice[]{slice};
            }
            else
            {
                List<ColumnSlice> l = new ArrayList<ColumnSlice>(startBounds.size());
                for (int i = 0; i < startBounds.size(); i++)
                {
                    ColumnSlice slice = new ColumnSlice(startBounds.get(i), endBounds.get(i));
                    if (!slice.isAlwaysEmpty(cfm.comparator, isReversed))
                        l.add(slice);
                }
                if (l.isEmpty())
                    return null;
                slices = l.toArray(new ColumnSlice[l.size()]);
            }

            return new SliceQueryFilter(slices, isReversed, limit, toGroup);
        }
        else
        {
            SortedSet<ByteBuffer> cellNames = getRequestedColumns(variables);
            if (cellNames == null) // in case of IN () for the last column of the key
                return null;
            QueryProcessor.validateCellNames(cellNames);
            return new NamesQueryFilter(cellNames, true);
        }
    }

    private int getLimit(List<ByteBuffer> variables) throws InvalidRequestException
    {
        int l = Integer.MAX_VALUE;
        if (limit != null)
        {
            ByteBuffer b = limit.bindAndGet(variables);
            if (b == null)
                throw new InvalidRequestException("Invalid null value of limit");

            try
            {
                Int32Type.instance.validate(b);
                l = Int32Type.instance.compose(b);
            }
            catch (MarshalException e)
            {
                throw new InvalidRequestException("Invalid limit value");
            }
        }

        if (l <= 0)
            throw new InvalidRequestException("LIMIT must be strictly positive");

        // Internally, we don't support exclusive bounds for slices. Instead,
        // we query one more element if necessary and exclude
        if (sliceRestriction != null && !sliceRestriction.isInclusive(Bound.START) && l != Integer.MAX_VALUE)
            l += 1;

        return l;
    }

    private Collection<ByteBuffer> getKeys(final List<ByteBuffer> variables) throws InvalidRequestException
    {
        List<ByteBuffer> keys = new ArrayList<ByteBuffer>();
        ColumnNameBuilder builder = cfm.getKeyNameBuilder();
        for (ColumnDefinition def : cfm.partitionKeyColumns())
        {
            Restriction r = keyRestrictions[def.position()];
            assert r != null && !r.isSlice();

            List<ByteBuffer> values = r.values(variables);

            if (builder.remainingCount() == 1)
            {
                for (ByteBuffer val : values)
                {
                    if (val == null)
                        throw new InvalidRequestException(String.format("Invalid null value for partition key part %s", def.name));
                    keys.add(builder.copy().add(val).build());
                }
            }
            else
            {
                // Note: for backward compatibility reasons, we let INs with 1 value slide
                if (values.size() != 1)
                    throw new InvalidRequestException("IN is only supported on the last column of the partition key");
                ByteBuffer val = values.get(0);
                if (val == null)
                    throw new InvalidRequestException(String.format("Invalid null value for partition key part %s", def.name));
                builder.add(val);
            }
        }
        return keys;
    }

    private ByteBuffer getKeyBound(Bound b, List<ByteBuffer> variables) throws InvalidRequestException
    {
        // Deal with unrestricted partition key components (special-casing is required to deal with 2i queries on the first
        // component of a composite partition key).
        for (int i = 0; i < keyRestrictions.length; i++)
            if (keyRestrictions[i] == null)
                return ByteBufferUtil.EMPTY_BYTE_BUFFER;

        // We deal with IN queries for keys in other places, so we know buildBound will return only one result
        return buildBound(b, cfm.partitionKeyColumns(), keyRestrictions, false, cfm.getKeyNameBuilder(), variables).get(0);
    }

    private Token getTokenBound(Bound b, List<ByteBuffer> variables, IPartitioner<?> p) throws InvalidRequestException
    {
        assert onToken;

        Restriction keyRestriction = keyRestrictions[0];
        ByteBuffer value;
        if (keyRestriction.isEQ())
        {
            value = keyRestriction.values(variables).get(0);
        }
        else
        {
            Restriction.Slice slice = (Restriction.Slice)keyRestriction;
            if (!slice.hasBound(b))
                return p.getMinimumToken();

            value = slice.bound(b, variables);
        }

        if (value == null)
            throw new InvalidRequestException("Invalid null token value");
        return p.getTokenFactory().fromByteArray(value);
    }

    private boolean includeKeyBound(Bound b)
    {
        for (Restriction r : keyRestrictions)
        {
            if (r == null)
                return true;
            else if (r.isSlice())
                return ((Restriction.Slice)r).isInclusive(b);
        }
        // All equality
        return true;
    }

    private boolean isColumnRange()
    {
        // Due to CASSANDRA-5762, we always do a slice for CQL3 tables (not dense, composite).
        // Static CF (non dense but non composite) never entails a column slice however
        if (!cfm.isDense())
            return cfm.hasCompositeComparator();

        // Otherwise (i.e. for compact table where we don't have a row marker anyway and thus don't care about CASSANDRA-5762),
        // it is a range query if it has at least one the column alias for which no relation is defined or is not EQ.
        for (Restriction r : columnRestrictions)
        {
            if (r == null || r.isSlice())
                return true;
        }
        return false;
    }

    private SortedSet<ByteBuffer> getRequestedColumns(List<ByteBuffer> variables) throws InvalidRequestException
    {
        assert !isColumnRange();

        ColumnNameBuilder builder = cfm.getColumnNameBuilder();
        Iterator<ColumnDefinition> idIter = cfm.clusteringColumns().iterator();
        for (Restriction r : columnRestrictions)
        {
            ColumnIdentifier id = idIter.next().name;
            assert r != null && !r.isSlice();

            List<ByteBuffer> values = r.values(variables);
            if (values.size() == 1)
            {
                ByteBuffer val = values.get(0);
                if (val == null)
                    throw new InvalidRequestException(String.format("Invalid null value for clustering key part %s", id));
                builder.add(val);
            }
            else
            {
                // We have a IN, which we only support for the last column.
                // If compact, just add all values and we're done. Otherwise,
                // for each value of the IN, creates all the columns corresponding to the selection.
                if (values.isEmpty())
                    return null;
                SortedSet<ByteBuffer> columns = new TreeSet<ByteBuffer>(cfm.comparator);
                Iterator<ByteBuffer> iter = values.iterator();
                while (iter.hasNext())
                {
                    ByteBuffer val = iter.next();
                    ColumnNameBuilder b = iter.hasNext() ? builder.copy() : builder;
                    if (val == null)
                        throw new InvalidRequestException(String.format("Invalid null value for clustering key part %s", id));
                    b.add(val);
                    if (cfm.isDense())
                        columns.add(b.build());
                    else
                        columns.addAll(addSelectedColumns(b));
                }
                return columns;
            }
        }

        return addSelectedColumns(builder);
    }

    private SortedSet<ByteBuffer> addSelectedColumns(ColumnNameBuilder builder)
    {
        if (cfm.isDense())
        {
            return FBUtilities.singleton(builder.build());
        }
        else
        {
            // Collections require doing a slice query because a given collection is a
            // non-know set of columns, so we shouldn't get there
            assert !selectACollection();

            SortedSet<ByteBuffer> columns = new TreeSet<ByteBuffer>(cfm.comparator);

            // We need to query the selected column as well as the marker
            // column (for the case where the row exists but has no columns outside the PK)
            // Two exceptions are "static CF" (non-composite non-compact CF) and "super CF"
            // that don't have marker and for which we must query all columns instead
            if (cfm.hasCompositeComparator() && !cfm.isSuper())
            {
                // marker
                columns.add(builder.copy().add(ByteBufferUtil.EMPTY_BYTE_BUFFER).build());

                // selected columns
                for (ColumnIdentifier id : selection.regularColumnsToFetch())
                    columns.add(builder.copy().add(id).build());
            }
            else
            {
                Iterator<ColumnDefinition> iter = cfm.regularColumns().iterator();
                while (iter.hasNext())
                {
                    ColumnDefinition def = iter.next();
                    ColumnNameBuilder b = iter.hasNext() ? builder.copy() : builder;
                    ByteBuffer cname = b.add(def.name).build();
                    columns.add(cname);
                }
            }
            return columns;
        }
    }

    private boolean selectACollection()
    {
        if (!cfm.hasCollections())
            return false;

        for (ColumnDefinition def : selection.getColumnsList())
        {
            if (def.type instanceof CollectionType)
                return true;
        }

        return false;
    }

    private List<ByteBuffer> buildBound(Bound bound,
                                        Collection<ColumnDefinition> defs,
                                        Restriction[] restrictions,
                                        boolean isReversed,
                                        ColumnNameBuilder builder,
                                        List<ByteBuffer> variables) throws InvalidRequestException
    {
        // The end-of-component of composite doesn't depend on whether the
        // component type is reversed or not (i.e. the ReversedType is applied
        // to the component comparator but not to the end-of-component itself),
        // it only depends on whether the slice is reversed
        Bound eocBound = isReversed ? Bound.reverse(bound) : bound;
        for (ColumnDefinition def : defs)
        {
            // In a restriction, we always have Bound.START < Bound.END for the "base" comparator.
            // So if we're doing a reverse slice, we must inverse the bounds when giving them as start and end of the slice filter.
            // But if the actual comparator itself is reversed, we must inversed the bounds too.
            Bound b = isReversed == isReversedType(def) ? bound : Bound.reverse(bound);
            Restriction r = restrictions[def.position()];
            if (r == null || (r.isSlice() && !((Restriction.Slice)r).hasBound(b)))
            {
                // There wasn't any non EQ relation on that key, we select all records having the preceding component as prefix.
                // For composites, if there was preceding component and we're computing the end, we must change the last component
                // End-Of-Component, otherwise we would be selecting only one record.
                return Collections.singletonList(builder.componentCount() > 0 && eocBound == Bound.END
                                                 ? builder.buildAsEndOfRange()
                                                 : builder.build());
            }

            if (r.isSlice())
            {
                Restriction.Slice slice = (Restriction.Slice)r;
                assert slice.hasBound(b);
                ByteBuffer val = slice.bound(b, variables);
                if (val == null)
                    throw new InvalidRequestException(String.format("Invalid null clustering key part %s", def.name));
                return Collections.singletonList(builder.add(val, slice.getRelation(eocBound, b)).build());
            }
            else
            {
                List<ByteBuffer> values = r.values(variables);
                if (values.size() != 1)
                {
                    // IN query, we only support it on the clustering column
                    assert def.position() == defs.size() - 1;
                    // The IN query might not have listed the values in comparator order, so we need to re-sort
                    // the bounds lists to make sure the slices works correctly (also, to avoid duplicates).
                    TreeSet<ByteBuffer> s = new TreeSet<ByteBuffer>(isReversed ? cfm.comparator.reverseComparator : cfm.comparator);
                    for (ByteBuffer val : values)
                    {
                        if (val == null)
                            throw new InvalidRequestException(String.format("Invalid null clustering key part %s", def.name));
                        ColumnNameBuilder copy = builder.copy().add(val);
                        // See below for why this
                        s.add((bound == Bound.END && copy.remainingCount() > 0) ? copy.buildAsEndOfRange() : copy.build());
                    }
                    return new ArrayList<ByteBuffer>(s);
                }

                ByteBuffer val = values.get(0);
                if (val == null)
                    throw new InvalidRequestException(String.format("Invalid null clustering key part %s", def.name));
                builder.add(val);
            }
        }
        // Means no relation at all or everything was an equal
        // Note: if the builder is "full", there is no need to use the end-of-component bit. For columns selection,
        // it would be harmless to do it. However, we use this method got the partition key too. And when a query
        // with 2ndary index is done, and with the the partition provided with an EQ, we'll end up here, and in that
        // case using the eoc would be bad, since for the random partitioner we have no guarantee that
        // builder.buildAsEndOfRange() will sort after builder.build() (see #5240).
        return Collections.singletonList((bound == Bound.END && builder.remainingCount() > 0) ? builder.buildAsEndOfRange() : builder.build());
    }

    private List<ByteBuffer> getRequestedBound(Bound b, List<ByteBuffer> variables) throws InvalidRequestException
    {
        assert isColumnRange();
        return buildBound(b, cfm.clusteringColumns(), columnRestrictions, isReversed, cfm.getColumnNameBuilder(), variables);
    }

    public List<IndexExpression> getIndexExpressions(List<ByteBuffer> variables) throws InvalidRequestException
    {
        if (!usesSecondaryIndexing || restrictedColumns.isEmpty())
            return Collections.emptyList();

        List<IndexExpression> expressions = new ArrayList<IndexExpression>();
        for (ColumnDefinition def : restrictedColumns)
        {
            Restriction restriction;
            switch (def.kind)
            {
                case PARTITION_KEY:
                    restriction = keyRestrictions[def.position()];
                    break;
                case CLUSTERING_COLUMN:
                    restriction = columnRestrictions[def.position()];
                    break;
                case REGULAR:
                    restriction = metadataRestrictions.get(def.name);
                    break;
                default:
                    // We don't allow restricting a COMPACT_VALUE for now in prepare.
                    throw new AssertionError();
            }

            if (restriction.isSlice())
            {
                Restriction.Slice slice = (Restriction.Slice)restriction;
                for (Bound b : Bound.values())
                {
                    if (slice.hasBound(b))
                    {
                        ByteBuffer value = slice.bound(b, variables);
                        if (value == null)
                            throw new InvalidRequestException(String.format("Unsupported null value for indexed column %s", def.name));
                        if (value.remaining() > 0xFFFF)
                            throw new InvalidRequestException("Index expression values may not be larger than 64K");
                        expressions.add(new IndexExpression(def.name.bytes, slice.getIndexOperator(b), value));
                    }
                }
            }
            else
            {
                List<ByteBuffer> values = restriction.values(variables);

                if (values.size() != 1)
                    throw new InvalidRequestException("IN restrictions are not supported on indexed columns");

                ByteBuffer value = values.get(0);
                if (value == null)
                    throw new InvalidRequestException(String.format("Unsupported null value for indexed column %s", def.name));
                if (value.remaining() > 0xFFFF)
                    throw new InvalidRequestException("Index expression values may not be larger than 64K");
                expressions.add(new IndexExpression(def.name.bytes, IndexExpression.Operator.EQ, value));
            }
        }
        return expressions;
    }


    private Iterable<Column> columnsInOrder(final ColumnFamily cf, final List<ByteBuffer> variables) throws InvalidRequestException
    {
        if (columnRestrictions.length == 0)
            return cf.getSortedColumns();

        // If the restriction for the last column alias is an IN, respect
        // requested order
        Restriction last = columnRestrictions[columnRestrictions.length - 1];
        if (last == null || last.isSlice())
            return cf.getSortedColumns();

        ColumnNameBuilder builder = cfm.getColumnNameBuilder();
        for (int i = 0; i < columnRestrictions.length - 1; i++)
            builder.add(columnRestrictions[i].values(variables).get(0));

        List<ByteBuffer> values = last.values(variables);
        final List<ByteBuffer> requested = new ArrayList<ByteBuffer>(values.size());
        Iterator<ByteBuffer> iter = values.iterator();
        while (iter.hasNext())
        {
            ByteBuffer t = iter.next();
            ColumnNameBuilder b = iter.hasNext() ? builder.copy() : builder;
            requested.add(b.add(t).build());
        }

        return new Iterable<Column>()
        {
            public Iterator<Column> iterator()
            {
                return new AbstractIterator<Column>()
                {
                    Iterator<ByteBuffer> iter = requested.iterator();
                    public Column computeNext()
                    {
                        if (!iter.hasNext())
                            return endOfData();
                        Column column = cf.getColumn(iter.next());
                        return column == null ? computeNext() : column;
                    }
                };
            }
        };
    }

    private ResultSet process(List<Row> rows, List<ByteBuffer> variables, int limit, long now) throws InvalidRequestException
    {
        Selection.ResultSetBuilder result = selection.resultSetBuilder(now);
        for (org.apache.cassandra.db.Row row : rows)
        {
            // Not columns match the query, skip
            if (row.cf == null)
                continue;

            processColumnFamily(row.key.key, row.cf, variables, now, result);
        }

        ResultSet cqlRows = result.build();

        orderResults(cqlRows);

        // Internal calls always return columns in the comparator order, even when reverse was set
        if (isReversed)
            cqlRows.reverse();

        // Trim result if needed to respect the limit
        cqlRows.trim(limit);
        return cqlRows;
    }

    // Used by ModificationStatement for CAS operations
    void processColumnFamily(ByteBuffer key, ColumnFamily cf, List<ByteBuffer> variables, long now, Selection.ResultSetBuilder result)
    throws InvalidRequestException
    {
        ByteBuffer[] keyComponents = cfm.getKeyValidator() instanceof CompositeType
                                   ? ((CompositeType)cfm.getKeyValidator()).split(key)
                                   : new ByteBuffer[]{ key };

        if (parameters.isDistinct)
        {
            if (!cf.hasOnlyTombstones(now))
            {
                result.newRow();
                // selection.getColumnsList() will contain only the partition key components - all of them.
                for (ColumnDefinition def : selection.getColumnsList())
                    result.add(keyComponents[def.position()]);
            }
        }
        else if (cfm.isDense())
        {
            // One cqlRow per column
            for (Column c : columnsInOrder(cf, variables))
            {
                if (c.isMarkedForDelete(now))
                    continue;

                ByteBuffer[] components = null;
                if (cfm.hasCompositeComparator())
                {
                    components = ((CompositeType)cfm.comparator).split(c.name());
                }
                else if (sliceRestriction != null)
                {
                    // For dynamic CF, the column could be out of the requested bounds, filter here
                    if (!sliceRestriction.isInclusive(Bound.START) && c.name().equals(sliceRestriction.bound(Bound.START, variables)))
                        continue;
                    if (!sliceRestriction.isInclusive(Bound.END) && c.name().equals(sliceRestriction.bound(Bound.END, variables)))
                        continue;
                }

                result.newRow();
                // Respect selection order
                for (ColumnDefinition def : selection.getColumnsList())
                {
                    switch (def.kind)
                    {
                        case PARTITION_KEY:
                            result.add(keyComponents[def.position()]);
                            break;
                        case CLUSTERING_COLUMN:
                            ByteBuffer val = cfm.hasCompositeComparator()
                                           ? (def.position() < components.length ? components[def.position()] : null)
                                           : c.name();
                            result.add(val);
                            break;
                        case COMPACT_VALUE:
                            result.add(c);
                            break;
                        case REGULAR:
                            // This should not happen for compact CF
                            throw new AssertionError();
                        default:
                            throw new AssertionError();
                    }
                }
            }
        }
        else if (cfm.hasCompositeComparator())
        {
            // Sparse case: group column in cqlRow when composite prefix is equal
            CompositeType composite = (CompositeType)cfm.comparator;

            ColumnGroupMap.Builder builder = new ColumnGroupMap.Builder(composite, cfm.hasCollections(), now);

            for (Column c : cf)
            {
                if (c.isMarkedForDelete(now))
                    continue;

                builder.add(c);
            }

            for (ColumnGroupMap group : builder.groups())
                handleGroup(selection, result, keyComponents, group);
        }
        else
        {
            if (cf.hasOnlyTombstones(now))
                return;

            // Static case: One cqlRow for all columns
            result.newRow();
            for (ColumnDefinition def : selection.getColumnsList())
            {
                if (def.kind == ColumnDefinition.Kind.PARTITION_KEY)
                    result.add(keyComponents[def.position()]);
                else
                    result.add(cf.getColumn(def.name.bytes));
            }
        }
    }

    /**
     * Orders results when multiple keys are selected (using IN)
     */
    private void orderResults(ResultSet cqlRows)
    {
        // There is nothing to do if
        //   a. there are no results,
        //   b. no ordering information where given,
        //   c. key restriction is a Range or not an IN expression
        if (cqlRows.size() == 0 || parameters.orderings.isEmpty() || isKeyRange || !keyIsInRelation)
            return;

        assert orderingIndexes != null;

        // optimization when only *one* order condition was given
        // because there is no point of using composite comparator if there is only one order condition
        if (parameters.orderings.size() == 1)
        {
            ColumnDefinition ordering = cfm.getColumnDefinition(parameters.orderings.keySet().iterator().next());
            Collections.sort(cqlRows.rows, new SingleColumnComparator(orderingIndexes.get(ordering), ordering.type));
            return;
        }

        // builds a 'composite' type for multi-column comparison from the comparators of the ordering components
        // and passes collected position information and built composite comparator to CompositeComparator to do
        // an actual comparison of the CQL rows.
        List<AbstractType<?>> types = new ArrayList<AbstractType<?>>(parameters.orderings.size());
        int[] positions = new int[parameters.orderings.size()];

        int idx = 0;
        for (ColumnIdentifier identifier : parameters.orderings.keySet())
        {
            ColumnDefinition orderingColumn = cfm.getColumnDefinition(identifier);
            types.add(orderingColumn.type);
            positions[idx++] = orderingIndexes.get(orderingColumn);
        }

        Collections.sort(cqlRows.rows, new CompositeComparator(types, positions));
    }

    private void handleGroup(Selection selection, Selection.ResultSetBuilder result, ByteBuffer[] keyComponents, ColumnGroupMap columns) throws InvalidRequestException
    {
        // Respect requested order
        result.newRow();
        for (ColumnDefinition def : selection.getColumnsList())
        {
            switch (def.kind)
            {
                case PARTITION_KEY:
                    result.add(keyComponents[def.position()]);
                    break;
                case CLUSTERING_COLUMN:
                    result.add(columns.getKeyComponent(def.position()));
                    break;
                case COMPACT_VALUE:
                    // This should not happen for SPARSE
                    throw new AssertionError();
                case REGULAR:
                    if (def.type.isCollection())
                    {
                        List<Pair<ByteBuffer, Column>> collection = columns.getCollection(def.name.bytes);
                        ByteBuffer value = collection == null
                                         ? null
                                         : ((CollectionType)def.type).serialize(collection);
                        result.add(value);
                    }
                    else
                    {
                        result.add(columns.getSimple(def.name.bytes));
                    }
                    break;
            }
        }
    }

    private static boolean isReversedType(ColumnDefinition def)
    {
        return def.type instanceof ReversedType;
    }

    private boolean columnFilterIsIdentity()
    {
        for (Restriction r : columnRestrictions)
        {
            if (r != null)
                return false;
        }
        return true;
    }

    public static class RawStatement extends CFStatement
    {
        private final Parameters parameters;
        private final List<RawSelector> selectClause;
        private final List<Relation> whereClause;
        private final Term.Raw limit;

        public RawStatement(CFName cfName, Parameters parameters, List<RawSelector> selectClause, List<Relation> whereClause, Term.Raw limit)
        {
            super(cfName);
            this.parameters = parameters;
            this.selectClause = selectClause;
            this.whereClause = whereClause == null ? Collections.<Relation>emptyList() : whereClause;
            this.limit = limit;
        }

        public ParsedStatement.Prepared prepare() throws InvalidRequestException
        {
            CFMetaData cfm = ThriftValidation.validateColumnFamily(keyspace(), columnFamily());

            VariableSpecifications names = getBoundsVariables();

            // Select clause
            if (parameters.isCount && !selectClause.isEmpty())
                throw new InvalidRequestException("Only COUNT(*) and COUNT(1) operations are currently supported.");

            Selection selection = selectClause.isEmpty()
                                ? Selection.wildcard(cfm)
                                : Selection.fromSelectors(cfm, selectClause);

            if (parameters.isDistinct)
                validateDistinctSelection(selection.getColumnsList(), cfm.partitionKeyColumns());

            Term prepLimit = null;
            if (limit != null)
            {
                prepLimit = limit.prepare(limitReceiver());
                prepLimit.collectMarkerSpecification(names);
            }

            SelectStatement stmt = new SelectStatement(cfm, names.size(), parameters, selection, prepLimit);

            /*
             * WHERE clause. For a given entity, rules are:
             *   - EQ relation conflicts with anything else (including a 2nd EQ)
             *   - Can't have more than one LT(E) relation (resp. GT(E) relation)
             *   - IN relation are restricted to row keys (for now) and conflicts with anything else
             *     (we could allow two IN for the same entity but that doesn't seem very useful)
             *   - The value_alias cannot be restricted in any way (we don't support wide rows with indexed value in CQL so far)
             */
            boolean hasQueriableIndex = false;
            boolean hasQueriableClusteringColumnIndex = false;
            for (Relation rel : whereClause)
            {
                ColumnDefinition def = cfm.getColumnDefinition(rel.getEntity());
                if (def == null)
                {
                    if (containsAlias(rel.getEntity()))
                        throw new InvalidRequestException(String.format("Aliases aren't allowed in where clause ('%s')", rel));
                    else
                        throw new InvalidRequestException(String.format("Undefined name %s in where clause ('%s')", rel.getEntity(), rel));
                }

                stmt.restrictedColumns.add(def);
                if (def.isIndexed() && rel.operator() == Relation.Type.EQ)
                {
                    hasQueriableIndex = true;
                    if (def.kind == ColumnDefinition.Kind.CLUSTERING_COLUMN)
                        hasQueriableClusteringColumnIndex = true;
                }

                switch (def.kind)
                {
                    case PARTITION_KEY:
                        stmt.keyRestrictions[def.position()] = updateRestriction(def, stmt.keyRestrictions[def.position()], rel, names);
                        break;
                    case CLUSTERING_COLUMN:
                        stmt.columnRestrictions[def.position()] = updateRestriction(def, stmt.columnRestrictions[def.position()], rel, names);
                        break;
                    case COMPACT_VALUE:
                        throw new InvalidRequestException(String.format("Predicates on the non-primary-key column (%s) of a COMPACT table are not yet supported", def.name));
                    case REGULAR:
                        // We only all IN on the row key and last clustering key so far, never on non-PK columns, and this even if there's an index
                        Restriction r = updateRestriction(def, stmt.metadataRestrictions.get(def), rel, names);
                        if (r.isIN() && !((Restriction.IN)r).canHaveOnlyOneValue())
                            // Note: for backward compatibility reason, we conside a IN of 1 value the same as a EQ, so we let that slide.
                            throw new InvalidRequestException(String.format("IN predicates on non-primary-key columns (%s) is not yet supported", def.name));
                        stmt.metadataRestrictions.put(def.name, r);
                        break;
                }
            }

            /*
             * At this point, the select statement if fully constructed, but we still have a few things to validate
             */

            // If there is a queriable index, no special condition are required on the other restrictions.
            // But we still need to know 2 things:
            //   - If we don't have a queriable index, is the query ok
            //   - Is it queriable without 2ndary index, which is always more efficient
            // If a component of the partition key is restricted by a relation, all preceding
            // components must have a EQ. Only the last partition key component can be in IN relation.
            boolean canRestrictFurtherComponents = true;
            ColumnDefinition previous = null;
            stmt.keyIsInRelation = false;
            Iterator<ColumnDefinition> iter = cfm.partitionKeyColumns().iterator();
            for (int i = 0; i < stmt.keyRestrictions.length; i++)
            {
                ColumnDefinition cdef = iter.next();
                Restriction restriction = stmt.keyRestrictions[i];

                if (restriction == null)
                {
                    if (stmt.onToken)
                        throw new InvalidRequestException("The token() function must be applied to all partition key components or none of them");

                    // The only time not restricting a key part is allowed is if none are restricted or an index is used.
                    if (i > 0 && stmt.keyRestrictions[i - 1] != null)
                    {
                        if (hasQueriableIndex)
                        {
                            stmt.usesSecondaryIndexing = true;
                            stmt.isKeyRange = true;
                            break;
                        }
                        throw new InvalidRequestException(String.format("Partition key part %s must be restricted since preceding part is", cdef.name));
                    }

                    stmt.isKeyRange = true;
                    canRestrictFurtherComponents = false;
                }
                else if (!canRestrictFurtherComponents)
                {
                    if (hasQueriableIndex)
                    {
                        stmt.usesSecondaryIndexing = true;
                        break;
                    }
                    throw new InvalidRequestException(String.format("partition key part %s cannot be restricted (preceding part %s is either not restricted or by a non-EQ relation)", cdef.name, previous));
                }
                else if (restriction.isOnToken())
                {
                    // If this is a query on tokens, it's necessarily a range query (there can be more than one key per token).
                    stmt.isKeyRange = true;
                    stmt.onToken = true;
                }
                else if (stmt.onToken)
                {
                    throw new InvalidRequestException(String.format("The token() function must be applied to all partition key components or none of them"));
                }
                else if (!restriction.isSlice())
                {
                    if (restriction.isIN())
                    {
                        // We only support IN for the last name so far
                        if (i != stmt.keyRestrictions.length - 1)
                            throw new InvalidRequestException(String.format("Partition KEY part %s cannot be restricted by IN relation (only the last part of the partition key can)", cdef.name));
                        stmt.keyIsInRelation = true;
                    }
                }
                else
                {
                    // Non EQ relation is not supported without token(), even if we have a 2ndary index (since even those are ordered by partitioner).
                    // Note: In theory we could allow it for 2ndary index queries with ALLOW FILTERING, but that would probably require some special casing
                    throw new InvalidRequestException("Only EQ and IN relation are supported on the partition key (unless you use the token() function)");
                }
                previous = cdef;
            }

            // All (or none) of the partition key columns have been specified;
            // hence there is no need to turn these restrictions into index expressions.
            if (!stmt.usesSecondaryIndexing)
                stmt.restrictedColumns.removeAll(cfm.partitionKeyColumns());

            // If a clustering key column is restricted by a non-EQ relation, all preceding
            // columns must have a EQ, and all following must have no restriction. Unless
            // the column is indexed that is.
            canRestrictFurtherComponents = true;
            previous = null;
            iter = cfm.clusteringColumns().iterator();
            for (int i = 0; i < stmt.columnRestrictions.length; i++)
            {
                ColumnDefinition cdef = iter.next();
                Restriction restriction = stmt.columnRestrictions[i];

                if (restriction == null)
                {
                    canRestrictFurtherComponents = false;
                }
                else if (!canRestrictFurtherComponents)
                {
                    if (hasQueriableIndex)
                    {
                        stmt.usesSecondaryIndexing = true; // handle gaps and non-keyrange cases.
                        break;
                    }
                    throw new InvalidRequestException(String.format("PRIMARY KEY part %s cannot be restricted (preceding part %s is either not restricted or by a non-EQ relation)", cdef.name, previous));
                }
                else if (restriction.isSlice())
                {
                    canRestrictFurtherComponents = false;
                    Restriction.Slice slice = (Restriction.Slice)restriction;
                    // For non-composite slices, we don't support internally the difference between exclusive and
                    // inclusive bounds, so we deal with it manually.
                    if (!cfm.hasCompositeComparator() && (!slice.isInclusive(Bound.START) || !slice.isInclusive(Bound.END)))
                        stmt.sliceRestriction = slice;
                }
                else if (restriction.isIN())
                {
                    // We only support IN for the last name and for compact storage so far
                    // TODO: #3885 allows us to extend to non compact as well, but that remains to be done
                    if (i != stmt.columnRestrictions.length - 1)
                        throw new InvalidRequestException(String.format("PRIMARY KEY part %s cannot be restricted by IN relation", cdef.name));
                    else if (stmt.selectACollection())
                        throw new InvalidRequestException(String.format("Cannot restrict PRIMARY KEY part %s by IN relation as a collection is selected by the query", cdef.name));
                }

                previous = cdef;
            }

            // Covers indexes on the first clustering column (among others).
            if (stmt.isKeyRange && hasQueriableClusteringColumnIndex)
                stmt.usesSecondaryIndexing = true;

            if (!stmt.usesSecondaryIndexing)
                stmt.restrictedColumns.removeAll(cfm.clusteringColumns());

            // Even if usesSecondaryIndexing is false at this point, we'll still have to use one if
            // there is restrictions not covered by the PK.
            if (!stmt.metadataRestrictions.isEmpty())
            {
                if (!hasQueriableIndex)
                    throw new InvalidRequestException("No indexed columns present in by-columns clause with Equal operator");
                stmt.usesSecondaryIndexing = true;
            }

            if (stmt.usesSecondaryIndexing && stmt.keyIsInRelation)
                throw new InvalidRequestException("Select on indexed columns and with IN clause for the PRIMARY KEY are not supported");

            if (!stmt.parameters.orderings.isEmpty())
            {
                if (stmt.usesSecondaryIndexing)
                    throw new InvalidRequestException("ORDER BY with 2ndary indexes is not supported.");

                if (stmt.isKeyRange)
                    throw new InvalidRequestException("ORDER BY is only supported when the partition key is restricted by an EQ or an IN.");

                // If we order an IN query, we'll have to do a manual sort post-query. Currently, this sorting requires that we
                // have queried the column on which we sort (TODO: we should update it to add the column on which we sort to the one
                // queried automatically, and then removing it from the resultSet afterwards if needed)
                if (stmt.keyIsInRelation)
                {
                    stmt.orderingIndexes = new HashMap<ColumnDefinition, Integer>();
                    for (ColumnIdentifier column : stmt.parameters.orderings.keySet())
                    {
                        final ColumnDefinition def = cfm.getColumnDefinition(column);
                        if (def == null)
                        {
                            if (containsAlias(column))
                                throw new InvalidRequestException(String.format("Aliases are not allowed in order by clause ('%s')", column));
                            else
                                throw new InvalidRequestException(String.format("Order by on unknown column %s", column));
                        }

                        if (selectClause.isEmpty()) // wildcard
                        {
                            stmt.orderingIndexes.put(def, Iterators.indexOf(cfm.allColumnsInSelectOrder(),
                                                                            new Predicate<ColumnDefinition>()
                                                                            {
                                                                                public boolean apply(ColumnDefinition n)
                                                                                {
                                                                                    return def.equals(n);
                                                                                }
                                                                            }));
                        }
                        else
                        {
                            boolean hasColumn = false;
                            for (int i = 0; i < selectClause.size(); i++)
                            {
                                RawSelector selector = selectClause.get(i);
                                if (def.name.equals(selector.selectable))
                                {
                                    stmt.orderingIndexes.put(def, i);
                                    hasColumn = true;
                                    break;
                                }
                            }

                            if (!hasColumn)
                                throw new InvalidRequestException("ORDER BY could not be used on columns missing in select clause.");
                        }
                    }
                }

                Boolean[] reversedMap = new Boolean[cfm.clusteringColumns().size()];
                int i = 0;
                for (Map.Entry<ColumnIdentifier, Boolean> entry : stmt.parameters.orderings.entrySet())
                {
                    ColumnIdentifier column = entry.getKey();
                    boolean reversed = entry.getValue();

                    ColumnDefinition def = cfm.getColumnDefinition(column);
                    if (def == null)
                    {
                        if (containsAlias(column))
                            throw new InvalidRequestException(String.format("Aliases are not allowed in order by clause ('%s')", column));
                        else
                            throw new InvalidRequestException(String.format("Order by on unknown column %s", column));
                    }

                    if (def.kind != ColumnDefinition.Kind.CLUSTERING_COLUMN)
                        throw new InvalidRequestException(String.format("Order by is currently only supported on the clustered columns of the PRIMARY KEY, got %s", column));

                    if (i++ != def.position())
                        throw new InvalidRequestException(String.format("Order by currently only support the ordering of columns following their declared order in the PRIMARY KEY"));

                    reversedMap[def.position()] = (reversed != isReversedType(def));
                }

                // Check that all boolean in reversedMap, if set, agrees
                Boolean isReversed = null;
                for (Boolean b : reversedMap)
                {
                    // Column on which order is specified can be in any order
                    if (b == null)
                        continue;

                    if (isReversed == null)
                    {
                        isReversed = b;
                        continue;
                    }
                    if (isReversed != b)
                        throw new InvalidRequestException(String.format("Unsupported order by relation"));
                }
                assert isReversed != null;
                stmt.isReversed = isReversed;
            }

            // Make sure this queries is allowed (note: non key range non indexed cannot involve filtering underneath)
            if (!parameters.allowFiltering && (stmt.isKeyRange || stmt.usesSecondaryIndexing))
            {
                // We will potentially filter data if either:
                //  - Have more than one IndexExpression
                //  - Have no index expression and the column filter is not the identity
                if (stmt.restrictedColumns.size() > 1 || (stmt.restrictedColumns.isEmpty() && !stmt.columnFilterIsIdentity()))
                    throw new InvalidRequestException("Cannot execute this query as it might involve data filtering and thus may have unpredictable performance. "
                                                    + "If you want to execute this query despite the performance unpredictability, use ALLOW FILTERING");
            }

            return new ParsedStatement.Prepared(stmt, names);
        }

        private void validateDistinctSelection(Collection<ColumnDefinition> requestedColumns, Collection<ColumnDefinition> partitionKey)
        throws InvalidRequestException
        {
            for (ColumnDefinition def : requestedColumns)
                if (!partitionKey.contains(def))
                    throw new InvalidRequestException(String.format("SELECT DISTINCT queries must only request partition key columns (not %s)", def.name));

            for (ColumnDefinition def : partitionKey)
                if (!requestedColumns.contains(def))
                    throw new InvalidRequestException(String.format("SELECT DISTINCT queries must request all the partition key columns (missing %s)", def.name));
        }

        private boolean containsAlias(final ColumnIdentifier name)
        {
            return Iterables.any(selectClause, new Predicate<RawSelector>()
                                               {
                                                   public boolean apply(RawSelector raw)
                                                   {
                                                       return name.equals(raw.alias);
                                                   }
                                               });
        }

        private ColumnSpecification limitReceiver()
        {
            return new ColumnSpecification(keyspace(), columnFamily(), new ColumnIdentifier("[limit]", true), Int32Type.instance);
        }

        Restriction updateRestriction(ColumnDefinition def, Restriction restriction, Relation newRel, VariableSpecifications boundNames) throws InvalidRequestException
        {
            ColumnSpecification receiver = def;
            if (newRel.onToken)
            {
                if (def.kind != ColumnDefinition.Kind.PARTITION_KEY)
                    throw new InvalidRequestException(String.format("The token() function is only supported on the partition key, found on %s", def.name));

                receiver = new ColumnSpecification(def.ksName,
                                                   def.cfName,
                                                   new ColumnIdentifier("partition key token", true),
                                                   StorageService.getPartitioner().getTokenValidator());
            }

            switch (newRel.operator())
            {
                case EQ:
                    {
                        if (restriction != null)
                            throw new InvalidRequestException(String.format("%s cannot be restricted by more than one relation if it includes an Equal", def.name));
                        Term t = newRel.getValue().prepare(receiver);
                        t.collectMarkerSpecification(boundNames);
                        restriction = new Restriction.EQ(t, newRel.onToken);
                    }
                    break;
                case IN:
                    if (restriction != null)
                        throw new InvalidRequestException(String.format("%s cannot be restricted by more than one relation if it includes a IN", def.name));

                    if (newRel.getInValues() == null)
                    {
                        // Means we have a "SELECT ... IN ?"
                        assert newRel.getValue() != null;
                        Term t = newRel.getValue().prepare(receiver);
                        t.collectMarkerSpecification(boundNames);
                        restriction = Restriction.IN.create(t);
                    }
                    else
                    {
                        List<Term> inValues = new ArrayList<Term>(newRel.getInValues().size());
                        for (Term.Raw raw : newRel.getInValues())
                        {
                            Term t = raw.prepare(receiver);
                            t.collectMarkerSpecification(boundNames);
                            inValues.add(t);
                        }
                        restriction = Restriction.IN.create(inValues);
                    }
                    break;
                case GT:
                case GTE:
                case LT:
                case LTE:
                    {
                        if (restriction == null)
                            restriction = new Restriction.Slice(newRel.onToken);
                        else if (!restriction.isSlice())
                            throw new InvalidRequestException(String.format("%s cannot be restricted by both an equal and an inequal relation", def.name));
                        Term t = newRel.getValue().prepare(receiver);
                        t.collectMarkerSpecification(boundNames);
                        ((Restriction.Slice)restriction).setBound(def.name, newRel.operator(), t);
                    }
                    break;
            }
            return restriction;
        }

        @Override
        public String toString()
        {
            return Objects.toStringHelper(this)
                          .add("name", cfName)
                          .add("selectClause", selectClause)
                          .add("whereClause", whereClause)
                          .add("isDistinct", parameters.isDistinct)
                          .add("isCount", parameters.isCount)
                          .toString();
        }
    }

    public static class Parameters
    {
        private final Map<ColumnIdentifier, Boolean> orderings;
        private final boolean isDistinct;
        private final boolean isCount;
        private final ColumnIdentifier countAlias;
        private final boolean allowFiltering;

        public Parameters(Map<ColumnIdentifier, Boolean> orderings,
                          boolean isDistinct,
                          boolean isCount,
                          ColumnIdentifier countAlias,
                          boolean allowFiltering)
        {
            this.orderings = orderings;
            this.isDistinct = isDistinct;
            this.isCount = isCount;
            this.countAlias = countAlias;
            this.allowFiltering = allowFiltering;
        }
    }

    /**
     * Used in orderResults(...) method when single 'ORDER BY' condition where given
     */
    private static class SingleColumnComparator implements Comparator<List<ByteBuffer>>
    {
        private final int index;
        private final AbstractType<?> comparator;

        public SingleColumnComparator(int columnIndex, AbstractType<?> orderer)
        {
            index = columnIndex;
            comparator = orderer;
        }

        public int compare(List<ByteBuffer> a, List<ByteBuffer> b)
        {
            return comparator.compare(a.get(index), b.get(index));
        }
    }

    /**
     * Used in orderResults(...) method when multiple 'ORDER BY' conditions where given
     */
    private static class CompositeComparator implements Comparator<List<ByteBuffer>>
    {
        private final List<AbstractType<?>> orderTypes;
        private final int[] positions;

        private CompositeComparator(List<AbstractType<?>> orderTypes, int[] positions)
        {
            this.orderTypes = orderTypes;
            this.positions = positions;
        }

        public int compare(List<ByteBuffer> a, List<ByteBuffer> b)
        {
            for (int i = 0; i < positions.length; i++)
            {
                AbstractType<?> type = orderTypes.get(i);
                int columnPos = positions[i];

                ByteBuffer aValue = a.get(columnPos);
                ByteBuffer bValue = b.get(columnPos);

                int comparison = type.compare(aValue, bValue);

                if (comparison != 0)
                    return comparison;
            }

            return 0;
        }
    }
}
