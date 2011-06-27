/*
 * Copyright 2009 Alberto Gimeno <gimenete at gmail.com>
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *   
 *   @author mandubian <pascal.voitot@mandubian.org>
 */
package siena.gae;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang.NotImplementedException;

import siena.AbstractPersistenceManager;
import siena.ClassInfo;
import siena.Query;
import siena.QueryAggregated;
import siena.SienaException;
import siena.Util;
import siena.core.ListQuery;
import siena.core.ListQuery4PM;
import siena.core.async.PersistenceManagerAsync;
import siena.core.options.QueryOptionFetchType;
import siena.core.options.QueryOptionOffset;
import siena.core.options.QueryOptionPage;
import siena.core.options.QueryOptionState;

import com.google.appengine.api.datastore.Cursor;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.QueryResultIterable;
import com.google.appengine.api.datastore.QueryResultList;
import com.google.appengine.api.datastore.Transaction;

public class GaePersistenceManager extends AbstractPersistenceManager {

	private DatastoreService ds;
	private PersistenceManagerAsync asyncPm;
	/*
	 * properties are not used but keeps it in case of...
	 */
	private Properties props;
	
	public static final String DB = "GAE";

	public void init(Properties p) {
		ds = DatastoreServiceFactory.getDatastoreService();
		props = p;
	}

	public <T> PersistenceManagerAsync async() {
		if(asyncPm==null){
			asyncPm = new GaePersistenceManagerAsync();
			asyncPm.init(props);
		}
		return asyncPm;		
	}

	
	public void beginTransaction(int isolationLevel) {
		ds.beginTransaction();
	}
	
	public void beginTransaction() {
		ds.beginTransaction();
	}

	public void closeConnection() {
		// does nothing
	}

	public void commitTransaction() {
		Transaction txn = ds.getCurrentTransaction();
		txn.commit();
	}

	public void rollbackTransaction() {
		Transaction txn = ds.getCurrentTransaction();
		txn.rollback();
	}
	
	public void delete(Object obj) {
		ds.delete(GaeMappingUtils.getKey(obj));
	}

	public void get(Object obj) {
		Key key = GaeMappingUtils.getKey(obj);
		ClassInfo info = ClassInfo.getClassInfo(obj.getClass());
		try {
			Entity entity = ds.get(key);
			GaeMappingUtils.fillModel(obj, entity);

			// aggregated management
			if(!info.aggregatedFields.isEmpty()){
				mapAggregated(obj);
			}
			
			// join management
			if(!info.joinFields.isEmpty()){
				mapJoins(obj);
			}

		} 
		catch (Exception e) {
			throw new SienaException(e);
		}
	}

	public <T> T getByKey(Class<T> clazz, Object key) {
		Key gKey = GaeMappingUtils.makeKey(clazz, key);
		ClassInfo info = ClassInfo.getClassInfo(clazz);
		try {
			Entity entity = ds.get(gKey);
			T obj = Util.createObjectInstance(clazz);
			GaeMappingUtils.fillModelAndKey(obj, entity);
					
			// aggregated management
			if(!info.aggregatedFields.isEmpty()){
				mapAggregated(obj);
			}
			
			// join management
			if(!info.joinFields.isEmpty()){
				mapJoins(obj);
			}
			
			return obj;
		} 
		catch(EntityNotFoundException e){
			return null;
		}
		catch (Exception e) {
			throw new SienaException(e);
		}
	}

	public void insert(Object obj) {
		_insertSingle(obj, null, null, null);	
	}

	private void _insertSingle(Object obj, final Entity parent, final ClassInfo parentInfo, final Field field) {
		Class<?> clazz = obj.getClass();
		ClassInfo info = ClassInfo.getClassInfo(clazz);
		Field idField = info.getIdField();
		final Entity entity;
		
		if(parent==null){
			entity = GaeMappingUtils.createEntityInstance(idField, info, obj);
			GaeMappingUtils.fillEntity(obj, entity);
			ds.put(entity);
			GaeMappingUtils.setIdFromKey(idField, obj, entity.getKey());
		}else {
			entity = GaeMappingUtils.createEntityInstanceFromParent(idField, info, obj, parent.getKey(), parentInfo, field);
			GaeMappingUtils.fillEntity(obj, entity);
			// fills the parent entity field with the key in the case of the One2One aggregated relation.
			// put them together in the db
			//String property = ClassInfo.getColumnNames(field)[0];
			//parent.setProperty(property, entity.getKey());
			//ds.put(new ArrayList<Entity>() {{ add(entity); add(parent); }});
			ds.put(entity);
			GaeMappingUtils.setIdFromKey(idField, obj, entity.getKey());
		}
		
		if(!info.aggregatedFields.isEmpty()){
			Map<Key, Map<Field, List<Object>>> keyMap = new HashMap<Key, Map<Field, List<Object>>>();
			Map<Field, List<Object>> objectMap = new HashMap<Field, List<Object>>();
			keyMap.put(entity.getKey(), objectMap);
			for(Field f: info.aggregatedFields){
				if(ClassInfo.isModel(f.getType())){
					Object aggObj = Util.readField(obj, f);
					//_insertSingle(aggObj, entity, info, f);
					objectMap.put(f, Arrays.asList(aggObj));
				}
				else if(ClassInfo.isListQuery(f)){
					ListQuery<?> lq = (ListQuery<?>)Util.readField(obj, f);
					if(!lq.elements().isEmpty()){
						//_insertMultiple(lq.elements(), entity, info, f);
						objectMap.put(f, (List<Object>)lq.elements());
					}
				}
			}
			
			_insertMultipleMapFromParent(keyMap, Arrays.asList(info));
		}
	}
	
	private int _insertMultiple(Object[] objects, final Entity parentEntity, final ClassInfo parentInfo, final Field field) {
		return _insertMultiple(Arrays.asList(objects), parentEntity, parentInfo, field);
	}
	
	private int _insertMultiple(Iterable<?> objects, final Entity parentEntity, final ClassInfo parentInfo, final Field field) {
		List<Entity> entities = new ArrayList<Entity>();
		if(parentEntity==null){
			for(Object obj:objects){
				Class<?> clazz = obj.getClass();
				ClassInfo info = ClassInfo.getClassInfo(clazz);
				Field idField = info.getIdField();
				Entity entity = GaeMappingUtils.createEntityInstance(idField, info, obj);
				GaeMappingUtils.fillEntity(obj, entity);
				entities.add(entity);
			}			
		}else {
			for(Object obj:objects){
				Class<?> clazz = obj.getClass();
				ClassInfo info = ClassInfo.getClassInfo(clazz);
				Field idField = info.getIdField();
				Entity entity = GaeMappingUtils.createEntityInstanceFromParent(
							idField, info, obj, 
							parentEntity.getKey(), parentInfo, field);
				GaeMappingUtils.fillEntity(obj, entity);
				entities.add(entity);
			}
		}
		
		List<Key> generatedKeys = ds.put(entities);
		
		int i=0;
		Map<Key, Map<Field, List<Object>>> keyMap = new HashMap<Key, Map<Field, List<Object>>>();
		List<ClassInfo> infos = new ArrayList<ClassInfo>();
		for(Object obj:objects){
			Class<?> clazz = obj.getClass();
			ClassInfo info = ClassInfo.getClassInfo(clazz);
			Field idField = info.getIdField();
			GaeMappingUtils.setIdFromKey(idField, obj, generatedKeys.get(i));
			
			if(!info.aggregatedFields.isEmpty()){
				infos.add(info);
				Map<Field, List<Object>> objectMap = new HashMap<Field, List<Object>>();		
				keyMap.put(generatedKeys.get(i), objectMap);
				for(Field f: info.aggregatedFields){
					if(ClassInfo.isModel(f.getType())){
						Object aggObj = Util.readField(obj, f);
						objectMap.put(f, Arrays.asList(aggObj));
					}
					else if(ClassInfo.isListQuery(f)){
						ListQuery<?> lq = (ListQuery<?>)Util.readField(obj, f);
						if(!lq.elements().isEmpty()){
							objectMap.put(f, (List<Object>)lq.elements());
						}
					}
				}
			}
			i++;
		}
		if(!keyMap.isEmpty()){
			_insertMultipleMapFromParent(keyMap, infos);
		}
		return generatedKeys.size();
	}
	
	private int _insertMultipleMapFromParent(final Map<Key, Map<Field, List<Object>>> keyMap, final List<ClassInfo> parentInfos) {
		List<Entity> entities = new ArrayList<Entity>();
		int i=0;
		for(Key key:keyMap.keySet()){
			Map<Field, List<Object>> objectMap = keyMap.get(key);
			for(Field field: objectMap.keySet()){
				for(Object obj:objectMap.get(field)){
					Class<?> clazz = obj.getClass();
					ClassInfo info = ClassInfo.getClassInfo(clazz);
					Field idField = info.getIdField();
					Entity entity = GaeMappingUtils.createEntityInstanceFromParent(idField, info, obj, key, parentInfos.get(i), field);
					GaeMappingUtils.fillEntity(obj, entity);
					entities.add(entity);
				}
			}
			i++;
		}
		
		List<Key> generatedKeys = ds.put(entities);
		
		i=0;
		Map<Key, Map<Field, List<Object>>> recKeyMap = new HashMap<Key, Map<Field, List<Object>>>();
		List<ClassInfo> recInfos = new ArrayList<ClassInfo>();
		for(Key key:keyMap.keySet()){
			Map<Field, List<Object>> objectMap = keyMap.get(key);
			for(Field field: objectMap.keySet()){
				for(Object obj:objectMap.get(field)){
					Class<?> clazz = obj.getClass();
					ClassInfo info = ClassInfo.getClassInfo(clazz);
					Field idField = info.getIdField();
					GaeMappingUtils.setIdFromKey(idField, obj, generatedKeys.get(i));
					
					if(!info.aggregatedFields.isEmpty()){
						recInfos.add(info);
						Map<Field, List<Object>> recObjectMap = new HashMap<Field, List<Object>>();		
						recKeyMap.put(generatedKeys.get(i), recObjectMap);
						for(Field f: info.aggregatedFields){
							if(ClassInfo.isModel(f.getType())){
								Object aggObj = Util.readField(obj, f);
								recObjectMap.put(f, Arrays.asList(aggObj));
							}
							else if(ClassInfo.isListQuery(f)){
								ListQuery<?> lq = (ListQuery<?>)Util.readField(obj, f);
								if(!lq.elements().isEmpty()){
									recObjectMap.put(f, (List<Object>)lq.elements());
								}
							}
						}
					}
					
					i++;
				}
			}
		}
		if(!recKeyMap.isEmpty()){
			_insertMultipleMapFromParent(recKeyMap, recInfos);
		}
		return generatedKeys.size();
	}
	
	public void update(Object obj) {
		List<Entity> entities = new ArrayList<Entity>(); 
		_buildUpdateList(entities, obj, null, null, null);
		
		ds.put(entities);
	}
	
	private void _buildUpdateList(List<Entity> entities, Object obj, Key parentKey, ClassInfo parentInfo, Field parentField){
		Class<?> clazz = obj.getClass();
		ClassInfo info = ClassInfo.getClassInfo(clazz);
		Field idField = info.getIdField();
		
		Entity entity;
		if(parentKey == null){
			entity = GaeMappingUtils.createEntityInstanceForUpdate(idField, info, obj);
		}else {
			entity = GaeMappingUtils.createEntityInstanceForUpdateFromParent(
					idField, info, obj, parentKey, parentInfo, parentField);
		}
		GaeMappingUtils.fillEntity(obj, entity);		
		entities.add(entity);
		
		for(Field f: info.aggregatedFields){
			if(ClassInfo.isModel(f.getType())){
				Object aggObj = Util.readField(obj, f);
				_buildUpdateList(entities, aggObj, entity.getKey(), info, f);
			}
			else if(ClassInfo.isListQuery(f)){
				ListQuery<?> lq = (ListQuery<?>)Util.readField(obj, f);
				if(!lq.elements().isEmpty()){
					for(Object elt : lq.elements()){
						_buildUpdateList(entities, elt, entity.getKey(), info, f);
					}					
				}
			}
		}

	}
	
	public void save(Object obj) {
		Class<?> clazz = obj.getClass();
		ClassInfo info = ClassInfo.getClassInfo(clazz);
		Field idField = info.getIdField();
		
		Entity entity;
		Object idVal = Util.readField(obj, idField);
		// id with null value means insert
		if(idVal == null){
			entity = GaeMappingUtils.createEntityInstance(idField, info, obj);
		}
		// id with not null value means update
		else{
			entity = GaeMappingUtils.createEntityInstanceForUpdate(idField, info, obj);			
		}
		
		GaeMappingUtils.fillEntity(obj, entity);
		ds.put(entity);
		
		if(idVal == null){
			GaeMappingUtils.setIdFromKey(idField, obj, entity.getKey());
		}
	}
	
	protected DatastoreService getDatastoreService() {
		return ds;
	}



	
	private <T> PreparedQuery prepare(Query<T> query) {
		Class<?> clazz = query.getQueriedClass();
		ClassInfo info = ClassInfo.getClassInfo(clazz);
		com.google.appengine.api.datastore.Query q;

		// manages aggregation at first
		List<QueryAggregated> aggregs = query.getAggregatees();
		if(aggregs.isEmpty()){
			q = new com.google.appengine.api.datastore.Query(info.tableName);
		}
		else if(aggregs.size() == 1){
			QueryAggregated aggreg = aggregs.get(0);
			
			q = new com.google.appengine.api.datastore.Query(
					GaeMappingUtils.getKindWithAncestorField(info, 
							ClassInfo.getClassInfo(aggreg.aggregator.getClass()), aggreg.field));
			q.setAncestor(GaeMappingUtils.getKey(aggreg.aggregator));
		}
		else {
			throw new SienaException("Only one aggregation per query allowed");
		}
		
		return ds.prepare(GaeQueryUtils.addFiltersOrders(query, q));
	}

	private <T> PreparedQuery prepareKeysOnly(Query<T> query) {
		Class<?> clazz = query.getQueriedClass();
		ClassInfo info = ClassInfo.getClassInfo(clazz);
		com.google.appengine.api.datastore.Query q;

		// manages aggregation at first
		List<QueryAggregated> aggregs = query.getAggregatees();
		if(aggregs.isEmpty()){
			q = new com.google.appengine.api.datastore.Query(ClassInfo.getClassInfo(clazz).tableName);
		}
		else if(aggregs.size() == 1){
			QueryAggregated aggreg = aggregs.get(0);
			
			q = new com.google.appengine.api.datastore.Query(
					GaeMappingUtils.getKindWithAncestorField(info, 
							ClassInfo.getClassInfo(aggreg.aggregator.getClass()), aggreg.field));
			q.setAncestor(GaeMappingUtils.getKey(aggreg.aggregator));
		}
		else {
			throw new SienaException("Only one aggregation per query allowed");
		}
		
		return ds.prepare(GaeQueryUtils.addFiltersOrders(query, q).setKeysOnly());
	}

	
	protected <T> T mapJoins(Query<T> query, T model) {
		try {
			// join queries
			Map<Field, ArrayList<Key>> fieldMap = GaeQueryUtils.buildJoinFieldKeysMap(query);
			
			// creates the list of joined entity keys to extract 
			for(Field field: fieldMap.keySet()){
				Key key = GaeMappingUtils.getKey(field.get(model));
				List<Key> keys = fieldMap.get(field);
				if(!keys.contains(key))
					keys.add(key);
			}
			
			Map<Field, Map<Key, Entity>> entityMap = 
				new HashMap<Field, Map<Key, Entity>>();

			try {
				// retrieves all joined entities per field
				for(Field field: fieldMap.keySet()){
					Map<Key, Entity> entities = ds.get(fieldMap.get(field));
					entityMap.put(field, entities);
				}
			}catch(Exception ex){
				throw new SienaException(ex);
			}
			// associates linked models to their models
			// linkedModels is just a map to contain entities already mapped
			Map<Key, Object> linkedModels = new HashMap<Key, Object>();
			Object linkedObj;
			Entity entity; 
			
			for(Field field: fieldMap.keySet()){
				Object objVal = field.get(model);
				Key key = GaeMappingUtils.getKey(objVal);
				linkedObj = linkedModels.get(key);
				if(linkedObj==null){
					entity = entityMap.get(field).get(key);
					linkedObj = objVal;
					GaeMappingUtils.fillModel(linkedObj, entity);
					linkedModels.put(key, linkedObj);
				}
			
				field.set(model, linkedObj);				
			}

			return model;
		} catch(IllegalAccessException ex){
			throw new SienaException(ex);
		}		
	}
	
	protected <T> T mapJoins(T model) {
		try {
			// join queries
			Map<Field, ArrayList<Key>> fieldMap = GaeQueryUtils.buildJoinFieldKeysMap(model);
			
			// creates the list of joined entity keys to extract 
			for(Field field: fieldMap.keySet()){
				Key key = GaeMappingUtils.getKey(field.get(model));
				List<Key> keys = fieldMap.get(field);
				if(!keys.contains(key))
					keys.add(key);
			}
			
			Map<Field, Map<Key, Entity>> entityMap = 
				new HashMap<Field, Map<Key, Entity>>();

			try {
				// retrieves all joined entities per field
				for(Field field: fieldMap.keySet()){
					Map<Key, Entity> entities = ds.get(fieldMap.get(field));
					entityMap.put(field, entities);
				}
			}catch(Exception ex){
				throw new SienaException(ex);
			}
			// associates linked models to their models
			// linkedModels is just a map to contain entities already mapped
			Map<Key, Object> linkedModels = new HashMap<Key, Object>();
			Object linkedObj;
			Entity entity; 
			
			for(Field field: fieldMap.keySet()){
				Object objVal = field.get(model);
				Key key = GaeMappingUtils.getKey(objVal);
				linkedObj = linkedModels.get(key);
				if(linkedObj==null){
					entity = entityMap.get(field).get(key);
					linkedObj = objVal;
					GaeMappingUtils.fillModel(linkedObj, entity);
					linkedModels.put(key, linkedObj);
				}
			
				field.set(model, linkedObj);				
			}

			return model;
		} catch(IllegalAccessException ex){
			throw new SienaException(ex);
		}		
	}
	
	protected <T> List<T> mapJoins(Query<T> query, List<T> models) {
		try {
			// join queries
			Map<Field, ArrayList<Key>> fieldMap = GaeQueryUtils.buildJoinFieldKeysMap(query);
			
			// creates the list of joined entity keys to extract 
			for (final T model : models) {
				for(Field field: fieldMap.keySet()){
                    Object objVal = Util.readField(model, field);
                    // our object is not linked to another object...so it doesn't have any key
                    if(objVal == null) {
                        continue;
                    }

                    Key key = GaeMappingUtils.getKey(objVal);
					List<Key> keys = fieldMap.get(field);
					if(!keys.contains(key))
						keys.add(key);
				}
			}
			
			Map<Field, Map<Key, Entity>> entityMap = 
				new HashMap<Field, Map<Key, Entity>>();

			try {
				// retrieves all joined entities per field
				for(Field field: fieldMap.keySet()){
					Map<Key, Entity> entities = ds.get(fieldMap.get(field));
					// gets the future here because we need it so we wait for it
					entityMap.put(field, entities);
				}
			}catch(Exception ex){
				throw new SienaException(ex);
			}
			// associates linked models to their models
			// linkedModels is just a map to contain entities already mapped
			Map<Key, Object> linkedModels = new HashMap<Key, Object>();
			Object linkedObj;
			Entity entity; 
			
			for (final T model : models) {
				for(Field field: fieldMap.keySet()){
					Object objVal = Util.readField(model, field);
                    // our object is not linked to another object...so it doesn't have any key
                    if(objVal == null) {
                        continue;
                    }

					Key key = GaeMappingUtils.getKey(objVal);
					linkedObj = linkedModels.get(key);
					if(linkedObj==null){
						entity = entityMap.get(field).get(key);
						linkedObj = objVal;
						GaeMappingUtils.fillModel(linkedObj, entity);
						linkedModels.put(key, linkedObj);
					}
				
					field.set(model, linkedObj);				
				}
			}
			return models;
		} catch(IllegalAccessException ex){
			throw new SienaException(ex);
		}		
	}
	
	protected <T> List<T> mapJoins(List<T> models) {
		try {
			// join queries
			Map<Field, ArrayList<Key>> fieldMap = null;
			
			// creates the list of joined entity keys to extract 
			for (final T model : models) {
				// initializes fieldMap
				if(fieldMap == null){
					fieldMap = GaeQueryUtils.buildJoinFieldKeysMap(model);
				}
				for(Field field: fieldMap.keySet()){
                    Object objVal = Util.readField(model, field);
                    // our object is not linked to another object...so it doesn't have any key
                    if(objVal == null) {
                        continue;
                    }

                    Key key = GaeMappingUtils.getKey(objVal);
					List<Key> keys = fieldMap.get(field);
					if(!keys.contains(key))
						keys.add(key);
				}
			}
			
			Map<Field, Map<Key, Entity>> entityMap = 
				new HashMap<Field, Map<Key, Entity>>();

			try {
				// retrieves all joined entities per field
				for(Field field: fieldMap.keySet()){
					Map<Key, Entity> entities = ds.get(fieldMap.get(field));
					// gets the future here because we need it so we wait for it
					entityMap.put(field, entities);
				}
			}catch(Exception ex){
				throw new SienaException(ex);
			}
			// associates linked models to their models
			// linkedModels is just a map to contain entities already mapped
			Map<Key, Object> linkedModels = new HashMap<Key, Object>();
			Object linkedObj;
			Entity entity; 
			
			for (final T model : models) {
				for(Field field: fieldMap.keySet()){
					Object objVal = Util.readField(model, field);
                    // our object is not linked to another object...so it doesn't have any key
                    if(objVal == null) {
                        continue;
                    }

					Key key = GaeMappingUtils.getKey(objVal);
					linkedObj = linkedModels.get(key);
					if(linkedObj==null){
						entity = entityMap.get(field).get(key);
						linkedObj = objVal;
						GaeMappingUtils.fillModel(linkedObj, entity);
						linkedModels.put(key, linkedObj);
					}
				
					field.set(model, linkedObj);				
				}
			}
			return models;
		} catch(IllegalAccessException ex){
			throw new SienaException(ex);
		}		
	}
	
	protected <T> void fillAggregated(ClassInfo info, T ancestor, Key ancestorKey) {
		// now gets aggregated one2one (one2many are retrieved by ListQuery except with @Join)
		for(Field f:info.aggregatedFields){
			Class<?> cClazz = f.getType();
			ClassInfo cInfo = ClassInfo.getClassInfo(cClazz);
			if(ClassInfo.isModel(cClazz)){
				// creates a query for fieldname:child_tablename
				com.google.appengine.api.datastore.Query q = 
					new com.google.appengine.api.datastore.Query(GaeMappingUtils.getKindWithAncestorField(cInfo, info, f));

				PreparedQuery pq = ds.prepare(q.setAncestor(ancestorKey));
				Entity cEntity = pq.asSingleEntity();
				Object cObj = Util.createObjectInstance(cClazz);
				GaeMappingUtils.fillModelAndKey(cObj, cEntity);
				Util.setField(ancestor, f, cObj);
			}
			// todo manage joined one2many listquery
			else if(ClassInfo.isListQuery(f)){
				ListQuery4PM<?> lq = (ListQuery4PM<?>)Util.readField(ancestor, f);
				// sets the sync flag to false to tell that it should be fetched when the listquery is accessed!
				lq.setSync(false);
			}
		}
	}
	
	
	protected <T> T mapAggregated(T model) {
		Class<?> clazz = model.getClass();
		ClassInfo info = ClassInfo.getClassInfo(clazz);
		
		Map<Field, ClassInfo> modelMap = new HashMap<Field, ClassInfo>();
		boolean hasListQuery = false;
		
		// we scan the aggregatedfields to find potential listquery
		// if there is a listquery, we don't try to use the KINDLESS request
		// because we don't know how many children the entity can have.
		// if there is NO listquery, we use the kindless request
		for(Field f: info.aggregatedFields){
			Class<?> fClazz = f.getType();
			ClassInfo fInfo = ClassInfo.getClassInfo(fClazz);
			if(ClassInfo.isModel(fClazz)){
				modelMap.put(f, fInfo);
			}
			else if(ClassInfo.isListQuery(f)){
				ListQuery4PM<?> lq = (ListQuery4PM<?>)Util.readField(model, f);
				// sets the sync flag to false to tell that it should be fetched when the listquery is accessed!
				lq.setSync(false);
				
				hasListQuery = true;
			}
		}
			
		if(!hasListQuery){
			// creates a kindless query to retrieve all subentities at once.
			com.google.appengine.api.datastore.Query q = 
				new com.google.appengine.api.datastore.Query();
			Key parentKey = GaeMappingUtils.getKey(model);
			
			q.setAncestor(parentKey);
			// this removes the parent from query
			q.addFilter(Entity.KEY_RESERVED_PROPERTY, 
					com.google.appengine.api.datastore.Query.FilterOperator.GREATER_THAN,
				    parentKey);
			
			PreparedQuery pq = ds.prepare(q);
			List<Entity> childEntities = pq.asList(FetchOptions.Builder.withDefaults());
			
			for(Field f: modelMap.keySet()){
				Class<?> fClazz = f.getType();
				ClassInfo fInfo = modelMap.get(f);
				String kind = GaeMappingUtils.getKindWithAncestorField(fInfo, info, f);
				Entity found = null;
				for(Entity e: childEntities){
					if(kind.equals(e.getKind())){
						found = e;
						childEntities.remove(e);
						break;
					}
				}
					
				if(found != null){
					Object fObj = GaeMappingUtils.mapEntity(found, fClazz);
					Util.setField(model, f, fObj);
				}
			}
		}	
		else {
			for(Field f: modelMap.keySet()){
				Class<?> fClazz = f.getType();
				ClassInfo fInfo = modelMap.get(f);
				String kind = GaeMappingUtils.getKindWithAncestorField(fInfo, info, f);
				com.google.appengine.api.datastore.Query q = 
					new com.google.appengine.api.datastore.Query(kind);
				Key parentKey = GaeMappingUtils.getKey(model);
				q.setAncestor(parentKey);
				PreparedQuery pq = ds.prepare(q);
				Entity childEntity = pq.asSingleEntity();
				Object fObj = GaeMappingUtils.mapEntity(childEntity, fClazz);
				Util.setField(model, f, fObj);
			}
		}
		return model;
	}
	

	
	protected <T> List<T> mapAggregated(List<T> models) {
		for (final T model : models) {
			mapAggregated(model);
		}
		
		return models;
	}

	
	protected <T> T map(Query<T> query, Entity entity) {
		Class<?> clazz = query.getQueriedClass();
		@SuppressWarnings("unchecked")
		T result = (T)GaeMappingUtils.mapEntity(entity, clazz);
		
		// aggregated management
		if(!ClassInfo.getClassInfo(clazz).aggregatedFields.isEmpty()){
			mapAggregated(result);
		}
		
		// join management
		if(!query.getJoins().isEmpty() || !ClassInfo.getClassInfo(clazz).joinFields.isEmpty())
			mapJoins(query, result);
		
		return result;
	}
	
	@SuppressWarnings("unchecked")
	protected <T> List<T> map(Query<T> query, List<Entity> entities) {
		Class<?> clazz = query.getQueriedClass();
		List<T> result = (List<T>) GaeMappingUtils.mapEntities(entities, clazz);
		
		// aggregated management
		if(!ClassInfo.getClassInfo(clazz).aggregatedFields.isEmpty()){
			mapAggregated(result);
		}

		// join management
		if(!query.getJoins().isEmpty() || !ClassInfo.getClassInfo(clazz).joinFields.isEmpty())
			mapJoins(query, result);
		
		return result;
	}


	private <T> List<T> doFetchList(Query<T> query, int limit, int offset) {
		QueryOptionGaeContext gaeCtx = (QueryOptionGaeContext)query.option(QueryOptionGaeContext.ID);
		if(gaeCtx==null){
			gaeCtx = new QueryOptionGaeContext();
			query.customize(gaeCtx);
		}
		
		QueryOptionState state = (QueryOptionState)query.option(QueryOptionState.ID);
		QueryOptionFetchType fetchType = (QueryOptionFetchType)query.option(QueryOptionFetchType.ID);
		FetchOptions fetchOptions = FetchOptions.Builder.withDefaults();

		QueryOptionPage pag = (QueryOptionPage)query.option(QueryOptionPage.ID);
		if(!pag.isPaginating()){
			// no pagination but pageOption active
			if(pag.isActive()){
				// if local limit is set, it overrides the pageOption.pageSize
				if(limit!=Integer.MAX_VALUE){
					gaeCtx.realPageSize = limit;
					fetchOptions.limit(gaeCtx.realPageSize);
					// pageOption is passivated to be sure it is not reused
					pag.passivate();
				}
				// using pageOption.pageSize
				else {
					gaeCtx.realPageSize = pag.pageSize;
					fetchOptions.limit(gaeCtx.realPageSize);
					// passivates the pageOption in stateless mode not to keep anything between 2 requests
					if(state.isStateless()){
						pag.passivate();
					}						
				}
			}
			else {
				if(limit != Integer.MAX_VALUE){
					gaeCtx.realPageSize = limit;
					fetchOptions.limit(gaeCtx.realPageSize);
				}
			}
		}else {
			// paginating so use the pagesize and don't passivate pageOption
			// local limit is not taken into account
			gaeCtx.realPageSize = pag.pageSize;
			fetchOptions.limit(gaeCtx.realPageSize);
		}

		QueryOptionOffset off = (QueryOptionOffset)query.option(QueryOptionOffset.ID);
		// if local offset has been set, uses it
		if(offset!=0){
			off.activate();
			off.offset = offset;
		}
						
		// if previousPage has detected there is no more data, simply returns an empty list
		if(gaeCtx.noMoreDataBefore){
			return new ArrayList<T>();
		}
						
		if(state.isStateless()) {
			if(pag.isPaginating()){
				if(off.isActive()){
					gaeCtx.realOffset+=off.offset;
					fetchOptions.offset(gaeCtx.realOffset);
					off.passivate();
				}else {
					fetchOptions.offset(gaeCtx.realOffset);
				}
			}else {
				// if stateless and not paginating, resets the realoffset to 0
				gaeCtx.realOffset = 0;
				if(off.isActive()){
					gaeCtx.realOffset=off.offset;
					fetchOptions.offset(gaeCtx.realOffset);
					off.passivate();
				}
			}
			
			switch(fetchType.fetchType){
			case KEYS_ONLY:
				{
					// uses iterable as it is the only async request for prepared query for the time being
					List<Entity> entities = prepareKeysOnly(query).asList(fetchOptions);
					// if paginating and 0 results then no more data else resets noMoreDataAfter
					if(pag.isPaginating()){
						if(entities.size() == 0){
							gaeCtx.noMoreDataAfter = true;
						}
						else {
							gaeCtx.noMoreDataAfter = false;
						}
					}
					return GaeMappingUtils.mapEntitiesKeysOnly(entities, query.getQueriedClass());
				}
			case NORMAL:
			default:
				{
					// uses iterable as it is the only async request for prepared query for the time being
					List<Entity> entities = prepare(query).asList(fetchOptions);
					// if paginating and 0 results then no more data else resets noMoreDataAfter
					if(pag.isPaginating()){
						if(entities.size() == 0){
							gaeCtx.noMoreDataAfter = true;
						}
						else {
							gaeCtx.noMoreDataAfter = false;
						}
					}
					return map(query, entities);
				}
			}

		}else {
			if(off.isActive()){
				// by default, we add the offset but it can be added with the realoffset 
				// in case of cursor desactivated
				fetchOptions.offset(off.offset);
				gaeCtx.realOffset+=off.offset;
				off.passivate();
			}
			
			// manages cursor limitations for IN and != operators with offsets
			if(!gaeCtx.isActive()){
				// cursor not yet created
				switch(fetchType.fetchType){
				case KEYS_ONLY:
					{
						PreparedQuery pq = prepareKeysOnly(query);
						if(!gaeCtx.useCursor){
							// then uses offset (in case of IN or != operators)
							//if(offset.isActive()){
							//	fetchOptions.offset(gaeCtx.realOffset);
							//}						
							fetchOptions.offset(gaeCtx.realOffset);
						}
						
						// we can't use real asynchronous function with cursors
						// so the page is extracted at once and wrapped into a SienaFuture
						QueryResultList<Entity> entities = pq.asQueryResultList(fetchOptions);

						// activates the GaeCtx now that it is initialised
						gaeCtx.activate();
						// sets the current cursor (in stateful mode, cursor is always kept for further use)
						if(pag.isPaginating()){
							Cursor cursor = entities.getCursor();
							if(cursor!=null){
								gaeCtx.addCursor(cursor.toWebSafeString());
							}
							
							// if paginating and 0 results then no more data else resets noMoreDataAfter
							if(entities.size()==0){
								gaeCtx.noMoreDataAfter = true;
							} else {
								gaeCtx.noMoreDataAfter = false;
							}
						}else{
							Cursor cursor = entities.getCursor();
							if(cursor!=null){
								gaeCtx.addAndMoveCursor(entities.getCursor().toWebSafeString());
							}
							// keeps track of the offset anyway if not paginating
							gaeCtx.realOffset+=entities.size();
						}											
						
						return GaeMappingUtils.mapEntitiesKeysOnly(entities, query.getQueriedClass());
					}
				case NORMAL:
				default:
					{
						PreparedQuery pq = prepare(query);
						if(!gaeCtx.useCursor){
							// then uses offset (in case of IN or != operators)
							//if(offset.isActive()){
							//	fetchOptions.offset(gaeCtx.realOffset);
							//}
							fetchOptions.offset(gaeCtx.realOffset);
						}
						// we can't use real asynchronous function with cursors
						// so the page is extracted at once and wrapped into a SienaFuture
						QueryResultList<Entity> entities = pq.asQueryResultList(fetchOptions);
						
						// activates the GaeCtx now that it is initialised
						gaeCtx.activate();
						// sets the current cursor (in stateful mode, cursor is always kept for further use)
						if(pag.isPaginating()){
							Cursor cursor = entities.getCursor();
							if(cursor!=null){
								gaeCtx.addCursor(cursor.toWebSafeString());
							}
							// if paginating and 0 results then no more data else resets noMoreDataAfter
							if(entities.size()==0){
								gaeCtx.noMoreDataAfter = true;
							} else {
								gaeCtx.noMoreDataAfter = false;
							}
						}else{
							Cursor cursor = entities.getCursor();
							if(cursor!=null){
								gaeCtx.addAndMoveCursor(entities.getCursor().toWebSafeString());
							}
							// keeps track of the offset anyway if not paginating
							gaeCtx.realOffset+=entities.size();
						}
						
						return map(query, entities);
					}
				}
				
			}else {
				switch(fetchType.fetchType){
				case KEYS_ONLY:
					{
						// we prepare the query each time
						PreparedQuery pq = prepareKeysOnly(query);
						QueryResultList<Entity> entities;
						if(!gaeCtx.useCursor){
							// then uses offset (in case of IN or != operators)
							//if(offset.isActive()){
							//	fetchOptions.offset(gaeCtx.realOffset);
							//}
							fetchOptions.offset(gaeCtx.realOffset);
							// we can't use real asynchronous function with cursors
							// so the page is extracted at once and wrapped into a SienaFuture
							entities = pq.asQueryResultList(fetchOptions);
						}else {
							// we can't use real asynchronous function with cursors
							// so the page is extracted at once and wrapped into a SienaFuture
							String cursor = gaeCtx.currentCursor();
							if(cursor!=null){
								entities = pq.asQueryResultList(
									fetchOptions.startCursor(Cursor.fromWebSafeString(cursor)));
							}
							else {
								entities = pq.asQueryResultList(fetchOptions);
							}
						}
						
						// sets the current cursor (in stateful mode, cursor is always kept for further use)
						if(pag.isPaginating()){
							Cursor cursor = entities.getCursor();
							if(cursor!=null){
								gaeCtx.addCursor(cursor.toWebSafeString());
							}
							// if paginating and 0 results then no more data else resets noMoreDataAfter
							if(entities.size()==0){
								gaeCtx.noMoreDataAfter = true;
							} else {
								gaeCtx.noMoreDataAfter = false;
							}
						}else{
							Cursor cursor = entities.getCursor();
							if(cursor!=null){
								gaeCtx.addAndMoveCursor(entities.getCursor().toWebSafeString());
							}
							// keeps track of the offset anyway if not paginating
							gaeCtx.realOffset+=entities.size();
						}
						//}
						
						return GaeMappingUtils.mapEntitiesKeysOnly(entities, query.getQueriedClass());
					}
				case NORMAL:
				default:
					{
						PreparedQuery pq = prepare(query);
						QueryResultList<Entity> entities;
						if(!gaeCtx.useCursor){
							// then uses offset (in case of IN or != operators)
							//if(offset.isActive()){
							//	fetchOptions.offset(gaeCtx.realOffset);
							//}
							
							fetchOptions.offset(gaeCtx.realOffset);
							// we can't use real asynchronous function with cursors
							// so the page is extracted at once and wrapped into a SienaFuture
							entities = pq.asQueryResultList(fetchOptions);
						}else {
							// we can't use real asynchronous function with cursors
							// so the page is extracted at once and wrapped into a SienaFuture
							String cursor = gaeCtx.currentCursor();
							if(cursor!=null){
								entities = pq.asQueryResultList(
									fetchOptions.startCursor(Cursor.fromWebSafeString(gaeCtx.currentCursor())));
							}else {
								entities = pq.asQueryResultList(fetchOptions);
							}
						}
						
						// sets the current cursor (in stateful mode, cursor is always kept for further use)
						if(pag.isPaginating()){
							Cursor cursor = entities.getCursor();
							if(cursor!=null){
								gaeCtx.addCursor(cursor.toWebSafeString());
							}
							// if paginating and 0 results then no more data else resets noMoreDataAfter
							if(entities.size()==0){
								gaeCtx.noMoreDataAfter = true;
							} else {
								gaeCtx.noMoreDataAfter = false;
							}
						}else{
							Cursor cursor = entities.getCursor();
							if(cursor!=null){
								gaeCtx.addAndMoveCursor(entities.getCursor().toWebSafeString());
							}
							// keeps track of the offset anyway
							gaeCtx.realOffset+=entities.size();
						}
						
						return map(query, entities);
					}
				}
			}
		}
	}
	
	
	private <T> Iterable<T> doFetchIterable(Query<T> query, int limit, int offset) {
		QueryOptionGaeContext gaeCtx = (QueryOptionGaeContext)query.option(QueryOptionGaeContext.ID);
		QueryOptionState state = (QueryOptionState)query.option(QueryOptionState.ID);
		QueryOptionFetchType fetchType = (QueryOptionFetchType)query.option(QueryOptionFetchType.ID);
				
		if(gaeCtx==null){
			gaeCtx = new QueryOptionGaeContext();
			query.customize(gaeCtx);
		}

		FetchOptions fetchOptions = FetchOptions.Builder.withDefaults();

		QueryOptionPage pag = (QueryOptionPage)query.option(QueryOptionPage.ID);
		if(!pag.isPaginating()){
			// no pagination but pageOption active
			if(pag.isActive()){
				// if local limit is set, it overrides the pageOption.pageSize
				if(limit!=Integer.MAX_VALUE){
					gaeCtx.realPageSize = limit;
					fetchOptions.limit(gaeCtx.realPageSize);
					// pageOption is passivated to be sure it is not reused
					pag.passivate();
				}
				// using pageOption.pageSize
				else {
					gaeCtx.realPageSize = pag.pageSize;
					fetchOptions.limit(gaeCtx.realPageSize);
					// passivates the pageOption in stateless mode not to keep anything between 2 requests
					if(state.isStateless()){
						pag.passivate();
					}						
				}
			}
			else {
				if(limit != Integer.MAX_VALUE){
					gaeCtx.realPageSize = limit;
					fetchOptions.limit(gaeCtx.realPageSize);
				}
			}
		}else {
			// paginating so use the pagesize and don't passivate pageOption
			// local limit is not taken into account
			gaeCtx.realPageSize = pag.pageSize;
			fetchOptions.limit(gaeCtx.realPageSize);
		}

		QueryOptionOffset off = (QueryOptionOffset)query.option(QueryOptionOffset.ID);
		// if local offset has been set, uses it
		if(offset!=0){
			off.activate();
			off.offset = offset;
		}
		
		// if previousPage has detected there is no more data, simply returns an empty list
		if(gaeCtx.noMoreDataBefore){
			return new ArrayList<T>();
		}
						
		if(state.isStateless()) {
			if(pag.isPaginating()){			
				if(off.isActive()){
					gaeCtx.realOffset+=off.offset;
					fetchOptions.offset(gaeCtx.realOffset);
					off.passivate();
				}else {
					fetchOptions.offset(gaeCtx.realOffset);
				}
			}else {
								
				// if stateless and not paginating, resets the realoffset to 0
				gaeCtx.realOffset = off.offset;
				if(off.isActive()){
					fetchOptions.offset(gaeCtx.realOffset);
					off.passivate();
				}
			}
			
			switch(fetchType.fetchType){
			case ITER:
			default:
				{
					// uses iterable as it is the only async request for prepared query for the time being
					Iterable<Entity> entities = prepare(query).asIterable(fetchOptions);
					return new GaeSienaIterable<T>(this, entities, query);
				}
			}
			
		}else {			
			if(off.isActive()){
				// by default, we add the offset but it can be added with the realoffset 
				// in case of cursor desactivated
				fetchOptions.offset(off.offset);
				gaeCtx.realOffset+=off.offset;
				off.passivate();
			}
			// manages cursor limitations for IN and != operators		
			if(!gaeCtx.isActive()){
				// cursor not yet created
				switch(fetchType.fetchType){
				case ITER:
				default:
					{
						PreparedQuery pq = prepare(query);
						
						if(pag.isPaginating()){
							// in case of pagination, we need to allow asynchronous calls such as:
							// QueryAsync<MyClass> query = pm.createQuery(MyClass).paginate(5).stateful().order("name");
							// SienaFuture<Iterable<MyClass>> future1 = query.iter();
							// SienaFuture<Iterable<MyClass>> future2 = query.nextPage().iter();
							// Iterable<MyClass> it = future1.get().iterator();
							// while(it.hasNext()) { // do it }
							// it = future2.get().iterator();
							// while(it.hasNext()) { // do it }
							
							// so we can't use the asQueryResultIterable as the cursor is not moved to the end of the current page
							// but moved at each call of iterable.iterator().next()
							// thus we use the List in this case to be able to move directly to the next page with cursors
							QueryResultList<Entity> entities = pq.asQueryResultList(fetchOptions);

							// activates the GaeCtx now that it is initialised
							gaeCtx.activate();
							// sets the current cursor (in stateful mode, cursor is always kept for further use)
							//if(gaeCtx.useCursor){
							Cursor cursor = entities.getCursor();
							if(cursor!=null){
								gaeCtx.addCursor(cursor.toWebSafeString());
							}
							//}
							return new GaeSienaIterable<T>(this, entities, query);
						}else {
							// if not paginating, we simply use the queryresultiterable and moves the current cursor
							// while iterating
							QueryResultIterable<Entity> entities = pq.asQueryResultIterable(fetchOptions);
							// activates the GaeCtx now that it is initialised
							gaeCtx.activate();
							return new GaeSienaIterableWithCursor<T>(this, entities, query);
						}
						
					}
				}
				
			}else {
				switch(fetchType.fetchType){
				case ITER:
				default:
					{
						PreparedQuery pq = prepare(query);
						if(pag.isPaginating()){
							// in case of pagination, we need to allow asynchronous calls such as:
							// QueryAsync<MyClass> query = pm.createQuery(MyClass).paginate(5).stateful().order("name");
							// SienaFuture<Iterable<MyClass>> future1 = query.iter();
							// SienaFuture<Iterable<MyClass>> future2 = query.nextPage().iter();
							// Iterable<MyClass> it = future1.get().iterator();
							// while(it.hasNext()) { // do it }
							// it = future2.get().iterator();
							// while(it.hasNext()) { // do it }
							
							// so we can't use the asQueryResultIterable as the cursor is not moved to the end of the current page
							// but moved at each call of iterable.iterator().next()
							// thus we use the List in this case to be able to move directly to the next page with cursors
							QueryResultList<Entity> entities;
							if(!gaeCtx.useCursor){
								// then uses offset (in case of IN or != operators)
								//if(offset.isActive()){
								//	fetchOptions.offset(gaeCtx.realOffset);
								//}
								fetchOptions.offset(gaeCtx.realOffset);
								entities = pq.asQueryResultList(fetchOptions);
							}else {
								String cursor = gaeCtx.currentCursor();
								if(cursor!=null){
									entities = pq.asQueryResultList(
										fetchOptions.startCursor(Cursor.fromWebSafeString(cursor)));
								}else {
									entities = pq.asQueryResultList(fetchOptions);
								}
								
								// sets the current cursor (in stateful mode, cursor is always kept for further use)
								//if(gaeCtx.useCursor){
								gaeCtx.addCursor(entities.getCursor().toWebSafeString());
								//}
							}
							return new GaeSienaIterable<T>(this, entities, query);
						}else {
							// if not paginating, we simply use the queryresultiterable and moves the current cursor
							// while iterating
							QueryResultIterable<Entity> entities;
							if(!gaeCtx.useCursor){
								// then uses offset (in case of IN or != operators)
								//if(offset.isActive()){
								//	fetchOptions.offset(gaeCtx.realOffset);
								//}
								fetchOptions.offset(gaeCtx.realOffset);
								entities = pq.asQueryResultIterable(fetchOptions);
							}else {
								String cursor = gaeCtx.currentCursor();
								if(cursor!=null){
									entities = pq.asQueryResultIterable(
										fetchOptions.startCursor(Cursor.fromWebSafeString(gaeCtx.currentCursor())));
								}else {
									entities = pq.asQueryResultIterable(fetchOptions);	
								}
							}
							return new GaeSienaIterableWithCursor<T>(this, entities, query);
						}
					}
				}
			}

		}
	}
	
	public <T> List<T> fetch(Query<T> query) {
		((QueryOptionFetchType)query.option(QueryOptionFetchType.ID)).fetchType=QueryOptionFetchType.Type.NORMAL;
//		if(!pag.isPaginating()){
//			if(pag.pageSize==0)
//				pag.passivate();
//		}
		return (List<T>)doFetchList(query, Integer.MAX_VALUE, 0);
	}

	public <T> List<T> fetch(Query<T> query, int limit) {
		((QueryOptionFetchType)query.option(QueryOptionFetchType.ID)).fetchType=QueryOptionFetchType.Type.NORMAL;
		
//		QueryOptionPage pag = (QueryOptionPage)query.option(QueryOptionPage.ID);
//		// use this limit only if not paginating
//		if(!pag.isPaginating()){
//			pag.activate();
//			pag.pageSize=limit;
//		}
		return (List<T>)doFetchList(query, limit, 0);
	}

	public <T> List<T> fetch(Query<T> query, int limit, Object offset) {
		((QueryOptionFetchType)query.option(QueryOptionFetchType.ID)).fetchType=QueryOptionFetchType.Type.NORMAL;
//		QueryOptionPage pag = (QueryOptionPage)query.option(QueryOptionPage.ID);
//		QueryOptionOffset off = (QueryOptionOffset)query.option(QueryOptionOffset.ID);
		// use this limit/offset only if not paginating
//		if(!pag.isPaginating()){
//			pag.activate();
//			pag.pageSize=limit;
//			off.activate();
//			off.offset = (Integer)offset;
//		}
		return (List<T>)doFetchList(query, limit, (Integer)offset);
	}

	public <T> int count(Query<T> query) {
		return prepare(query)
				.countEntities(FetchOptions.Builder.withDefaults());
	}

	public <T> int delete(Query<T> query) {
		final ArrayList<Key> keys = new ArrayList<Key>();

		for (final Entity entity : prepareKeysOnly(query).asIterable(
				FetchOptions.Builder.withDefaults())) {
			keys.add(entity.getKey());
		}

		ds.delete(keys);

		return keys.size();
	}

	public <T> List<T> fetchKeys(Query<T> query) {
		((QueryOptionFetchType)query.option(QueryOptionFetchType.ID)).fetchType=QueryOptionFetchType.Type.KEYS_ONLY;
//		QueryOptionPage pag = (QueryOptionPage)query.option(QueryOptionPage.ID);
//		if(!pag.isPaginating()){
//			pag.passivate();
//		}

		return (List<T>)doFetchList(query, Integer.MAX_VALUE, 0);
	}

	public <T> List<T> fetchKeys(Query<T> query, int limit) {
		((QueryOptionFetchType)query.option(QueryOptionFetchType.ID)).fetchType=QueryOptionFetchType.Type.KEYS_ONLY;
//		QueryOptionPage pag = (QueryOptionPage)query.option(QueryOptionPage.ID);
		// use this limit only if not paginating
//		if(!pag.isPaginating()){
//			pag.activate();
//			pag.pageSize=limit;
//		}

		return (List<T>)doFetchList(query, limit, 0);
	}

	public <T> List<T> fetchKeys(Query<T> query, int limit, Object offset) {
		((QueryOptionFetchType)query.option(QueryOptionFetchType.ID)).fetchType=QueryOptionFetchType.Type.KEYS_ONLY;
//		QueryOptionPage pag = (QueryOptionPage)query.option(QueryOptionPage.ID);
//		QueryOptionOffset off = (QueryOptionOffset)query.option(QueryOptionOffset.ID);
		// use this limit/offset only if not paginating
//		if(!pag.isPaginating()){
//			pag.activate();
//			pag.pageSize=limit;
//			off.activate();
//			off.offset = (Integer)offset;
//		}

		return (List<T>)doFetchList(query, limit, (Integer)offset);
	}

	public <T> Iterable<T> iter(Query<T> query) {
		((QueryOptionFetchType)query.option(QueryOptionFetchType.ID)).fetchType=QueryOptionFetchType.Type.ITER;
//		QueryOptionPage pag = (QueryOptionPage)query.option(QueryOptionPage.ID);
//		if(!pag.isPaginating()){
//			pag.passivate();
//		}

		return doFetchIterable(query, Integer.MAX_VALUE, 0);
	}

	public <T> Iterable<T> iter(Query<T> query, int limit) {
		((QueryOptionFetchType)query.option(QueryOptionFetchType.ID)).fetchType=QueryOptionFetchType.Type.ITER;
//		QueryOptionPage pag = (QueryOptionPage)query.option(QueryOptionPage.ID);
//		// use this limit only if not paginating
//		if(!pag.isPaginating()){
//			pag.activate();
//			pag.pageSize=limit;
//		}

		return doFetchIterable(query, limit, 0);
	}

	public <T> Iterable<T> iter(Query<T> query, int limit, Object offset) {
		((QueryOptionFetchType)query.option(QueryOptionFetchType.ID)).fetchType=QueryOptionFetchType.Type.ITER;
//		QueryOptionPage pag = (QueryOptionPage)query.option(QueryOptionPage.ID);
//		QueryOptionOffset off = (QueryOptionOffset)query.option(QueryOptionOffset.ID);
//		// use this limit/offset only if not paginating
//		if(!pag.isPaginating()){
//			pag.activate();
//			pag.pageSize=limit;
//			off.activate();
//			off.offset = (Integer)offset;
//		}

		return doFetchIterable(query, limit, (Integer)offset);
	}


	public <T> void release(Query<T> query) {
		super.release(query);
		GaeQueryUtils.release(query);
	}
	
	public <T> void paginate(Query<T> query) {
		GaeQueryUtils.paginate(query);
	}

	public <T> void nextPage(Query<T> query) {
		GaeQueryUtils.nextPage(query);
	}

	public <T> void previousPage(Query<T> query) {
		GaeQueryUtils.previousPage(query);
	}

	public int insert(Object... objects) {
		/*List<Entity> entities = new ArrayList<Entity>(objects.length);
		for(int i=0; i<objects.length;i++){
			Class<?> clazz = objects[i].getClass();
			ClassInfo info = ClassInfo.getClassInfo(clazz);
			Field idField = info.getIdField();
			Entity entity = GaeMappingUtils.createEntityInstance(idField, info, objects[i]);
			GaeMappingUtils.fillEntity(objects[i], entity);
			entities.add(entity);
		}
				
		List<Key> generatedKeys =  ds.put(entities);
		
		int i=0;
		for(Object obj:objects){
			Class<?> clazz = obj.getClass();
			ClassInfo info = ClassInfo.getClassInfo(clazz);
			Field idField = info.getIdField();
			GaeMappingUtils.setIdFromKey(idField, obj, generatedKeys.get(i++));
		}
		
		return generatedKeys.size();*/
		
		return _insertMultiple(objects, null, null, null);
	}

	public int insert(Iterable<?> objects) {
		/*List<Entity> entities = new ArrayList<Entity>();
		for(Object obj:objects){
			Class<?> clazz = obj.getClass();
			ClassInfo info = ClassInfo.getClassInfo(clazz);
			Field idField = info.getIdField();
			Entity entity = GaeMappingUtils.createEntityInstance(idField, info, obj);
			GaeMappingUtils.fillEntity(obj, entity);
			entities.add(entity);
		}
				
		List<Key> generatedKeys = ds.put(entities);
		
		int i=0;
		for(Object obj:objects){
			Class<?> clazz = obj.getClass();
			ClassInfo info = ClassInfo.getClassInfo(clazz);
			Field idField = info.getIdField();
			GaeMappingUtils.setIdFromKey(idField, obj, generatedKeys.get(i++));
		}
		return generatedKeys.size();*/
		
		return _insertMultiple(objects, null, null, null);
	}

	public int delete(Object... models) {
		List<Key> keys = new ArrayList<Key>();
		for(Object obj:models){
			keys.add(GaeMappingUtils.getKey(obj));
		}
		
		ds.delete(keys);
		
		return keys.size();
	}


	public int delete(Iterable<?> models) {
		List<Key> keys = new ArrayList<Key>();
		for(Object obj:models){
			keys.add(GaeMappingUtils.getKey(obj));
		}
		
		ds.delete(keys);
		
		return keys.size();
	}


	public <T> int deleteByKeys(Class<T> clazz, Object... keys) {
		List<Key> gaeKeys = new ArrayList<Key>();
		for(Object key:keys){
			gaeKeys.add(GaeMappingUtils.makeKey(clazz, key));
		}
		
		ds.delete(gaeKeys);
		
		return gaeKeys.size();
	}

	public <T> int deleteByKeys(Class<T> clazz, Iterable<?> keys) {
		List<Key> gaeKeys = new ArrayList<Key>();
		for(Object key:keys){
			gaeKeys.add(GaeMappingUtils.makeKey(clazz, key));
		}
		
		ds.delete(gaeKeys);

		return gaeKeys.size();
	}


	public int get(Object... objects) {
		List<Key> keys = new ArrayList<Key>();
		for(Object obj:objects){
			keys.add(GaeMappingUtils.getKey(obj));
		}
		
		Map<Key, Entity> entityMap = ds.get(keys);
		
		for(Object obj:objects){
			GaeMappingUtils.fillModel(obj, entityMap.get(GaeMappingUtils.getKey(obj)));
		}
		
		return entityMap.size();
	}

	public <T> int get(Iterable<T> objects) {
		List<Key> keys = new ArrayList<Key>();
		for(Object obj:objects){
			keys.add(GaeMappingUtils.getKey(obj));
		}
		
		Map<Key, Entity> entityMap = ds.get(keys);
		
		for(Object obj:objects){
			GaeMappingUtils.fillModel(obj, entityMap.get(GaeMappingUtils.getKey(obj)));
		}
		
		return entityMap.size();
	}

	public <T> List<T> getByKeys(Class<T> clazz, Object... keys) {
		return getByKeys(clazz, Arrays.asList(keys));		
	}

	public <T> List<T> getByKeys(Class<T> clazz, Iterable<?> keys) {
		List<Key> gaeKeys = new ArrayList<Key>();
		ClassInfo info = ClassInfo.getClassInfo(clazz);
		
		for(Object key:keys){
			gaeKeys.add(GaeMappingUtils.makeKey(clazz, key));
		}
		
		Map<Key, Entity> entityMap = ds.get(gaeKeys);
		List<T> models = new ArrayList<T>(entityMap.size());
		for(Object key:keys){
			T obj = GaeMappingUtils.mapEntity(entityMap.get(GaeMappingUtils.makeKey(clazz, key)), clazz);
			
			// aggregated management
			if(!info.aggregatedFields.isEmpty()){
				mapAggregated(obj);
			}
			
			// join management
			if(!info.joinFields.isEmpty()){
				mapJoins(obj);
			}
			
			models.add(obj);
		}
		
		return models;
	}


	public <T> int update(Object... objects) {
		return update(Arrays.asList(objects));
	}

	public <T> int update(Iterable<T> objects) {
		List<Entity> entities = new ArrayList<Entity>();
		for(Object obj:objects){
			_buildUpdateList(entities, obj, null, null, null);
		}
				
		List<Key> generatedKeys = ds.put(entities);
		
		return generatedKeys.size();
	}

	public <T> int update(Query<T> query, Map<String, ?> fieldValues) {
		throw new NotImplementedException("update not implemented for GAE yet");
	}

	
	public int save(Object... objects) {
		List<Entity> entities = new ArrayList<Entity>();
		for(Object obj:objects){
			Class<?> clazz = obj.getClass();
			ClassInfo info = ClassInfo.getClassInfo(clazz);
			Field idField = info.getIdField();
			
			Entity entity;
			Object idVal = Util.readField(obj, idField);
			// id with null value means insert
			if(idVal == null){
				entity = GaeMappingUtils.createEntityInstance(idField, info, obj);
			}
			// id with not null value means update
			else{
				entity = GaeMappingUtils.createEntityInstanceForUpdate(idField, info, obj);			
			}
			
			GaeMappingUtils.fillEntity(obj, entity);
			entities.add(entity);			
		}
		
		List<Key> generatedKeys = ds.put(entities);
		
		int i=0;
		for(Object obj:objects){
			Class<?> clazz = obj.getClass();
			ClassInfo info = ClassInfo.getClassInfo(clazz);
			Field idField = info.getIdField();
			Object idVal = Util.readField(obj, idField);
			if(idVal == null){
				GaeMappingUtils.setIdFromKey(idField, obj, generatedKeys.get(i++));
			}
		}
		return generatedKeys.size();
	}

	public int save(Iterable<?> objects) {
		List<Entity> entities = new ArrayList<Entity>();
		for(Object obj:objects){
			Class<?> clazz = obj.getClass();
			ClassInfo info = ClassInfo.getClassInfo(clazz);
			Field idField = info.getIdField();
			
			Entity entity;
			Object idVal = Util.readField(obj, idField);
			// id with null value means insert
			if(idVal == null){
				entity = GaeMappingUtils.createEntityInstance(idField, info, obj);
			}
			// id with not null value means update
			else{
				entity = GaeMappingUtils.createEntityInstanceForUpdate(idField, info, obj);			
			}
			
			GaeMappingUtils.fillEntity(obj, entity);
			entities.add(entity);			
		}
		
		List<Key> generatedKeys = ds.put(entities);
		
		int i=0;
		for(Object obj:objects){
			Class<?> clazz = obj.getClass();
			ClassInfo info = ClassInfo.getClassInfo(clazz);
			Field idField = info.getIdField();
			Object idVal = Util.readField(obj, idField);
			if(idVal == null){
				GaeMappingUtils.setIdFromKey(idField, obj, generatedKeys.get(i++));
			}
		}
		return generatedKeys.size();
	}
	
	private static String[] supportedOperators;

	static {
		supportedOperators = GaeQueryUtils.operators.keySet().toArray(new String[0]);
	}	

	public String[] supportedOperators() {
		return supportedOperators;
	}


}
