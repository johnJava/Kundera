/*******************************************************************************
 *  * Copyright 2016 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.client.kudu.query;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EntityType;

import org.eclipse.persistence.jpa.jpql.parser.AndExpression;
import org.eclipse.persistence.jpa.jpql.parser.ComparisonExpression;
import org.eclipse.persistence.jpa.jpql.parser.Expression;
import org.eclipse.persistence.jpa.jpql.parser.JPQLExpression;
import org.eclipse.persistence.jpa.jpql.parser.WhereClause;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Type;
import org.apache.kudu.client.KuduClient;
import org.apache.kudu.client.KuduPredicate;
import org.apache.kudu.client.KuduScanner;
import org.apache.kudu.client.KuduScanner.KuduScannerBuilder;
import org.apache.kudu.client.KuduTable;
import org.apache.kudu.client.RowResult;
import org.apache.kudu.client.RowResultIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.impetus.client.kudu.KuduDBClient;
import com.impetus.client.kudu.KuduDBDataHandler;
import com.impetus.client.kudu.KuduDBValidationClassMapper;
import com.impetus.kundera.KunderaException;
import com.impetus.kundera.client.Client;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.MetamodelImpl;
import com.impetus.kundera.metadata.model.attributes.AbstractAttribute;
import com.impetus.kundera.persistence.EntityManagerFactoryImpl.KunderaMetadata;
import com.impetus.kundera.persistence.EntityReader;
import com.impetus.kundera.persistence.PersistenceDelegator;
import com.impetus.kundera.query.KunderaQuery;
import com.impetus.kundera.query.KunderaQueryUtils;
import com.impetus.kundera.query.Query;
import com.impetus.kundera.query.QueryImpl;
import com.impetus.kundera.utils.KunderaCoreUtils;

/**
 * The Class KuduDBQuery.
 * 
 * @author karthikp.manchala
 */
public class KuduDBQuery extends QueryImpl implements Query
{

    /** The logger. */
    private static Logger logger = LoggerFactory.getLogger(KuduDBQuery.class);

    /**
     * Instantiates a new kudu db query.
     * 
     * @param kunderaQuery
     *            the kundera query
     * @param persistenceDelegator
     *            the persistence delegator
     * @param kunderaMetadata
     *            the kundera metadata
     */
    public KuduDBQuery(KunderaQuery kunderaQuery, PersistenceDelegator persistenceDelegator,
            KunderaMetadata kunderaMetadata)
    {
        super(kunderaQuery, persistenceDelegator, kunderaMetadata);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.kundera.query.QueryImpl#populateEntities(com.impetus.kundera.
     * metadata.model.EntityMetadata, com.impetus.kundera.client.Client)
     */
    @Override
    protected List populateEntities(EntityMetadata m, Client client)
    {
        List results = new ArrayList();
        MetamodelImpl metaModel = (MetamodelImpl) kunderaMetadata.getApplicationMetadata()
                .getMetamodel(m.getPersistenceUnit());
        EntityType entityType = metaModel.entity(m.getEntityClazz());

        KuduClient kuduClient = ((KuduDBClient) client).getKuduClient();
        KuduTable table;
        try
        {
            table = kuduClient.openTable(m.getTableName());
        }
        catch (Exception e)
        {
            logger.error("Cannot open table : " + m.getTableName(), e);
            throw new KunderaException("Cannot open table : " + m.getTableName(), e);
        }
        KuduScannerBuilder scannerBuilder = kuduClient.newScannerBuilder(table);
        JPQLExpression jpqlExp = kunderaQuery.getJpqlExpression();
        List<String> selectColumns = KunderaQueryUtils.getSelectColumns(jpqlExp);
        if (!selectColumns.isEmpty())
        {
            // select by specific columns, set projection
            scannerBuilder.setProjectedColumnNames(selectColumns);
        }
        if (KunderaQueryUtils.hasWhereClause(jpqlExp))
        {
            // add predicate filters
            WhereClause whereClause = KunderaQueryUtils.getWhereClause(jpqlExp);
            Expression whereExp = whereClause.getConditionalExpression();
            parseAndBuildFilters(entityType, scannerBuilder, whereExp);
        }

        KuduScanner scanner = scannerBuilder.build();

        Object entity = null;
        while (scanner.hasMoreRows())
        {
            RowResultIterator rowResultIter;
            try
            {
                rowResultIter = scanner.nextRows();
            }
            catch (Exception e)
            {
                logger.error("Cannot get results from table : " + m.getTableName(), e);
                throw new KunderaException("Cannot get results from table : " + m.getTableName(), e);
            }

            while (rowResultIter.hasNext())
            {
                RowResult result = rowResultIter.next();
                entity = KunderaCoreUtils.createNewInstance(m.getEntityClazz());
                // populate RowResult to entity object and return
                ((KuduDBClient) client).populateEntity(entity, result, entityType);
                results.add(entity);
                logger.debug(result.rowToString());
            }
        }
        return results;
    }

    /**
     * Parses the and build filters.
     * 
     * @param entityType
     *            the entity type
     * @param scannerBuilder
     *            the scanner builder
     * @param whereExp
     *            the where exp
     */
    private void parseAndBuildFilters(EntityType entityType, KuduScannerBuilder scannerBuilder, Expression whereExp)
    {
        if (whereExp instanceof ComparisonExpression)
        {
            String left = ((ComparisonExpression) whereExp).getLeftExpression().toActualText();

            String right = ((ComparisonExpression) whereExp).getRightExpression().toActualText();
            Attribute attribute = entityType.getAttribute(left.split("[.]")[1]);
            addColumnRangePredicateToBuilder((Field) attribute.getJavaMember(), scannerBuilder,
                    ((AbstractAttribute) attribute).getJPAColumnName(), right,
                    ((ComparisonExpression) whereExp).getActualIdentifier());
        }
        else if (whereExp instanceof AndExpression)
        {
            parseAndBuildFilters(entityType, scannerBuilder, ((AndExpression) whereExp).getLeftExpression());
            parseAndBuildFilters(entityType, scannerBuilder, ((AndExpression) whereExp).getRightExpression());
        }
        else
        {
            logger.error("Operation not supported");
            throw new KunderaException("Operation not supported");
        }
    }

    /**
     * Adds the column range predicate to builder.
     * 
     * @param field
     *            the entity type
     * @param scannerBuilder
     *            the scanner builder
     * @param columnName
     *            the column name
     * @param value
     *            the value
     * @param identifier
     *            the identifier
     */
    private void addColumnRangePredicateToBuilder(Field field, KuduScannerBuilder scannerBuilder, String columnName,
            String value, String identifier)
    {
        Type type = KuduDBValidationClassMapper.getValidTypeForClass(field.getType());
        ColumnSchema column = new ColumnSchema.ColumnSchemaBuilder(columnName, type).build();

        KuduPredicate predicate;

        Object valueObject = KuduDBDataHandler.parse(type, value);

        switch (identifier)
        {
        case ">=":
            predicate = KuduDBDataHandler.getPredicate(column, KuduPredicate.ComparisonOp.GREATER_EQUAL, type,
                    valueObject);
            break;
        case ">":
            predicate = KuduDBDataHandler.getPredicate(column, KuduPredicate.ComparisonOp.GREATER, type, valueObject);
            break;
        case "<":
            predicate = KuduDBDataHandler.getPredicate(column, KuduPredicate.ComparisonOp.LESS, type, valueObject);
            break;
        case "<=":
            predicate = KuduDBDataHandler.getPredicate(column, KuduPredicate.ComparisonOp.LESS_EQUAL, type,
                    valueObject);
            break;
        case "=":
            predicate = KuduDBDataHandler.getPredicate(column, KuduPredicate.ComparisonOp.EQUAL, type, valueObject);
            break;
        default:
            logger.error("Operation not supported");
            throw new KunderaException("Operation not supported");
        }

        scannerBuilder.addPredicate(predicate);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.kundera.query.QueryImpl#findUsingLucene(com.impetus.kundera.
     * metadata.model.EntityMetadata, com.impetus.kundera.client.Client)
     */
    @Override
    protected List findUsingLucene(EntityMetadata m, Client client)
    {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.query.QueryImpl#recursivelyPopulateEntities(com.
     * impetus.kundera.metadata.model.EntityMetadata ,
     * com.impetus.kundera.client.Client)
     */
    @Override
    protected List recursivelyPopulateEntities(EntityMetadata m, Client client)
    {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.query.QueryImpl#getReader()
     */
    @Override
    protected EntityReader getReader()
    {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.query.QueryImpl#onExecuteUpdate()
     */
    @Override
    protected int onExecuteUpdate()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.query.QueryImpl#close()
     */
    @Override
    public void close()
    {
        // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.query.QueryImpl#iterate()
     */
    @Override
    public Iterator iterate()
    {
        // TODO Auto-generated method stub
        return null;
    }

}
