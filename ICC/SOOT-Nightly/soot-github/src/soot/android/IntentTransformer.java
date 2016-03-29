package soot.android;

import java.util.Iterator;



import java.util.*;
import java.lang.annotation.*;
import java.net.URI;
import java.io.PrintStream;

import soot.Body;
import soot.BodyTransformer;
import soot.Local;
import soot.PackManager;
import soot.PatchingChain;
import soot.RefType;
import soot.Type;
import soot.ArrayType;
import soot.VoidType;
import soot.NullType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.SootField;
import soot.SootFieldRef;
import soot.MethodSource;
import soot.Modifier;
import soot.Transform;
import soot.Unit;
import soot.Value;
import soot.util.*;
import soot.jimple.AbstractStmtSwitch;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.jimple.Jimple;
import soot.jimple.StringConstant;
import soot.options.Options;
import soot.tagkit.*; 
import soot.intentResolve.IntentFilterRecord;
import soot.intentResolve.intentDB;
import soot.intentResolve.myUri;

//import android.net.Uri

public class IntentTransformer extends BodyTransformer {

	public IntentTransformer(){
		super();
		int i = intentDB.openIntentDB(false);
		if(i>0){
			this.archivedIntentFilters = intentDB.retrieveFilters();
		}
		else{
			this.archivedIntentFilters = new ArrayList<IntentFilterRecord>();
		}
		intentDB.closeIntentDB();
		this.targetedIntents = new HashMap<String, SootClass>();
		this.uriHelpers = new HashMap<Value, String>();
		this.filterHelpers = new HashMap<Value, IntentFilterRecord>();
		this.helpers = new HashMap<SootClass, intentHelper>();
		this.stringHelpers = new HashMap<Value, String>();
		this.rnd = new Random();

	}

	private static IntentTransformer instance = new IntentTransformer();

	public static IntentTransformer v() { return instance; }

	private SootClass intentClass;

	private SootClass intentFilterClass;

	private SootClass contentResolverClass;

	class intentInfo{
		public IntentFilterRecord ifr;
		public SootClass intentClass;
		public String targetedClass;
		intentInfo(IntentFilterRecord IFR, SootClass iC, String tC){
			ifr = IFR;
			intentClass = iC;
			targetedClass = tC;
		}
	}

	/*class intentExtraInfo{
		public String fieldStr;
		public Value argVal;
		public Stmt originalStmt;
		public PatchingChain<Unit> bodyUnit; 

		intentExtraInfo(String fStr, Value aVal, Stmt orStmt, PatchingChain<Unit> bUnit){
			fieldStr = fStr;
			argVal = aVal;
			originalStmt = orStmt;
			bodyUnit = bUnit;
		}
	}*/

	class intentHelper{
		//public ArrayList<intentExtraInfo> extraInfos;
		public ArrayList<intentInfo> infos;
		public ArrayList<String> categories;
		public String originClass;
		public SootClass returningIntent;
		/*public String intentAction;
		public String intentPackage;*/

		intentHelper(){
			//extraInfos = new ArrayList<intentExtraInfo>();
			infos = new ArrayList<intentInfo>();
			categories = new ArrayList<String>();
			originClass = "";
			returningIntent = null;
		}

	}
	private ArrayList<IntentFilterRecord> archivedIntentFilters;

	private Map<String, SootClass> targetedIntents;

	private Map<Value, String> uriHelpers;

	private Map<Value, IntentFilterRecord> filterHelpers;

	private Map<Value, String> stringHelpers;

	private Map<SootClass, intentHelper> helpers;

	private String prefix = "edu.rpi.content.";

	private Random rnd;

	private SootClass getIntentClass() {
		if (intentClass == null) 
			intentClass = Scene.v().getSootClass("android.content.Intent");
		if (intentFilterClass == null)
			intentFilterClass = Scene.v().getSootClass("android.content.IntentFilter");
		return intentClass;
	}

	private void ICCLog(String str){
		if(Options.v().verbose()){
			System.err.println(str);
		}
		else{
			System.err.println(str);
		}
		return;
	}

	private SootClass getContentResolverClass(){
		if (contentResolverClass == null){
			contentResolverClass = Scene.v().getSootClass("android.content.ContentResolver");
		}
		return contentResolverClass;
	}

	private String encode(String s) {
		return prefix + "Intent_" + s.replace('/', '_').replace('.', '_').replace('$', '_');
	}

	private boolean isGoodIntent(IntentFilterRecord ifr, String opsString, String opsField){
		boolean result = true;
		switch (opsField){
		case "Provider":
			result = false;
			ICCLog("	Provider Elim:");
			ICCLog("	"+ ifr.getActionName() + "  " + opsString + " " + URI.create(opsString).getAuthority());
			if(ifr.getActionName().equals(URI.create(opsString).getAuthority())){
				result = true;
			}
			break;

		case "Data":
			//uri
			result = false;
			if(opsString == null || opsString ==""){
				result = true;
				break;
			}
			for(myUri furi: ifr.getDataURIs()){
				if(furi.match(opsString)){
					result = true;
					break;
				}
			}
			break;
		case "Type":
			result = ((ifr.getDataTypes().size()!=0)&& 
					(ifr.getDataTypes().contains(opsString)));
			break;
		case "Package":
			break;
		case "Action":
			if(ifr.getActionName()!=null){
				result = (ifr.getActionName().equals(opsString));
			}
			else{
				result = false;
			}
			break;
			
		default:
		}
		return result;
	}


	private void intentFilterElimination(intentHelper iH, String opsString, String opsField){
		for(Iterator<intentInfo> it = iH.infos.iterator(); it.hasNext();){
			intentInfo ifo = it.next();
			if(ifo.ifr != null && !isGoodIntent(ifo.ifr, opsString, opsField)){
				it.remove();
			}
		}

	}
	private SootClass modIntentTarget(SootClass c, String target, Local base, Stmt stmt, Chain units, Body b){
		String name = encode(target);
		SootClass targetedC = targetedIntents.get(name);
		ICCLog("	modifying Intent Target:");
		intentHelper iH = helpers.get(c);
		int type= 0;
		if(iH==null){
			iH = new intentHelper();
		}
		if (targetedC == null){
			//Class with given name is not targeted by any intent
			targetedC = c;
			targetedIntents.put(name, targetedC);
			type =1;

		}
		else if(c.getFieldCount()==0){
			base.setType(targetedC.getType());
			type =2;
		}
		else{
			//both intent classes are defined, need to transfer fields from one to the other

			
			for(SootField osf : c.getFields()){
				if(osf.getName().startsWith("edu.rpi.content.Intent")){
					Local temp = Jimple.v().newLocal(osf.getName(), RefType.v("java.lang.Object"));
					b.getLocals().add(temp);
					SootField sf = null;
					sf = getFieldByName(targetedC, osf.getName());
					if (sf==null){
						sf = new SootField(osf.getName(), RefType.v("java.lang.Object"), 
								Modifier.STATIC | Modifier.PUBLIC);
						targetedC.addField(sf);
					}
					Stmt assignStmt1 = Jimple.v().newAssignStmt(
							temp, 
							Jimple.v().newStaticFieldRef(osf.makeRef()));
					Stmt assignStmt2 = Jimple.v().newAssignStmt(
							Jimple.v().newStaticFieldRef(sf.makeRef()),
							temp);
					units.insertBefore(assignStmt1, stmt);
					units.insertBefore(assignStmt2, stmt);
				}
				else{
					continue;
				}
				type =3;
			}

		}
		ICCLog("	modType: " + type);
		return targetedC;

	}


	private SootClass getTargetedIntent(String target) {
		String name = encode(target);
		SootClass sc = targetedIntents.get(name);
		if (sc == null) {
			ICCLog("	intent class not exist, creating");
			sc = new SootClass(name, Modifier.PUBLIC);
			sc.setSuperclass(getIntentClass());
			Scene.v().addClass(sc);
			sc.setApplicationClass();
			targetedIntents.put(name, sc);
		}
		else{
			ICCLog("	class found");
		}
		return sc;
	}

	private SootClass createIntent(Local base, boolean isImplicit){
		SootClass sc = getTargetedIntent(Integer.toString(rnd.nextInt(100000000)));
		base.setType(sc.getType());
		intentHelper iH = new intentHelper();
		if ((!archivedIntentFilters.isEmpty()) && isImplicit){
			for(IntentFilterRecord ifr:archivedIntentFilters){
				String tcName = ifr.getTarget();
				/*if(tcount==0){
					c0 = modIntentTarget(c0, tcName, base, stmt, units, b);
				}*/


				//targetedIntents.put(tcName, sc);

				intentInfo ifo = new intentInfo(ifr, sc, tcName);
				iH.infos.add(ifo);

			}
		}
		helpers.put(sc, iH);
		return sc;		
	}
	private boolean isIntent(Value v){
		boolean result = (v.getType().toString().equals("android.content.Intent")||
				v.getType().toString().startsWith("edu.rpi.content.Intent"));
		return result;
	}

	private void resolveImplicitIntent(SootClass c0, String targetName, Local base, Stmt stmt, Chain units, Body b){


		ArrayList<IntentFilterRecord> ifrs;
		intentDB.openIntentDB(false);
		ifrs  = intentDB.retrieveFilters(targetName);
		intentHelper iH = helpers.get(c0);

		//take care of multi target implicit intents
		ICCLog("resolving implicit intent by action:");
		ICCLog("	action name: " + targetName);
		int tcount=0;
		//SootClass c;
		//SootClass c2 = new SootClass("placeholder");
		ICCLog("	components found that can handle the intent: " + tcount);

		return;
	}

	private boolean isBinder(Value val){
		SootClass binderClass = Scene.v().getSootClass("android.os.Binder");
		Type binderType = binderClass.getType();
		boolean result=false;
		if(val.getType() instanceof RefType){
			if(((RefType)(val.getType())).getSootClass().hasSuperclass()){
				result = ((RefType)(val.getType())).getSootClass().getSuperclass().equals(binderClass);
			}
		}
		return result;
	}

	/*private IntentFilterRecord putFilter(SootClass c){

	}*/

	public SootField getFieldByName(SootClass sc, String name) {
		for (SootField field : sc.getFields() ) {
			if(field.getName().equals(name)) {
				return field;
			}
		}
		return null;
	}

	public SootMethod getMethodByName(SootClass sc, String name) 
	{
		for (SootMethod method : sc.getMethods()) {
			if(method.getName().equals(name))
			{
				return method;
			}
		}
		return null;
	}

	private Set<SootClass> getSuperTypes(SootClass sc) {
		Set<SootClass> supertypes = new LinkedHashSet<SootClass>();

		Deque<SootClass> stack = new ArrayDeque<SootClass>();
		stack.push(sc);

		while (!stack.isEmpty()) {
			SootClass current = stack.pop();

			if (current.getName().equals("java.lang.Object"))
				continue;

			SootClass c = current.hasSuperclass() ? current.getSuperclass() : null;
			if (c!= null && !supertypes.contains(c)) {
				stack.push(c);
				supertypes.add(c);
			}
			for (SootClass supertype : current.getInterfaces()) {
				if (!supertypes.contains(supertype)) {
					stack.push(supertype);
					supertypes.add(supertype);
				}
			}
		}
		return supertypes;
	}

	protected SootMethod getDeclaringMethod(SootMethod method) {
		if (!method.isPhantom())
			return method;
		SootClass sc = method.getDeclaringClass();
		Set<SootClass> superTypes = getSuperTypes(sc);
		for (SootClass superClass : superTypes) {
			if (superClass.declaresMethod(method.getSubSignature())) {
				SootMethod m = superClass.getMethod(method.getSubSignature());
				if (!m.isPhantom())
					return m;
			}
		}
		return method;
	}

	@Deprecated
	private SootMethod createPutExtrMethod(String name, SootField field) {
		// create method
		SootMethod m = new SootMethod(name, 
				Arrays.asList(new Type[] { RefType.v("java.lang.Object") }),
				VoidType.v(), 
				Modifier.PUBLIC | Modifier.STATIC);

		JimpleBody body = Jimple.v().newBody(m);
		m.setActiveBody(body);
		Chain units = body.getUnits();
		Local arg;
		arg = Jimple.v().newLocal("r0", RefType.v("java.lang.Object"));
		body.getLocals().add(arg);
		units.add(Jimple.v().newIdentityStmt(arg, 
				Jimple.v().newParameterRef(RefType.v("java.lang.Object"), 0)));
		units.add(Jimple.v().newAssignStmt(
				Jimple.v().newStaticFieldRef(field.makeRef()), arg));
		units.add(Jimple.v().newReturnVoidStmt());

		return m;
	}

	@Deprecated
	private SootMethod createGetExtraMethod(String name, SootField field) {
		// create method
		SootMethod m = new SootMethod(name, 
				Arrays.asList(new Type[] {  }),
				RefType.v("java.lang.Object"), 
				Modifier.PUBLIC | Modifier.STATIC);

		JimpleBody body = Jimple.v().newBody(m);
		m.setActiveBody(body);
		Chain units = body.getUnits();
		Local arg;
		arg = Jimple.v().newLocal("r0", RefType.v("java.lang.Object"));
		body.getLocals().add(arg);
		units.add(Jimple.v().newAssignStmt(arg,
				Jimple.v().newStaticFieldRef(field.makeRef())));
		units.add(Jimple.v().newReturnStmt(arg));

		return m;
	}

	private int containsTargetedIntentArg(InstanceInvokeExpr e) {
		SootMethod invoke = e.getMethod();
		if (!invoke.getName().startsWith("start"))
			return -1;
		for (int i = 0; i < e.getArgCount(); i++) {
			Type t = e.getArg(i).getType();
			if (t instanceof RefType) {
				String encoded = encode(((RefType) t).getClassName());
				if (targetedIntents.containsKey(encoded))
					return i;
			}
		}
		return -1;
	}

	@Override
	protected void internalTransform(final Body b, String phaseName, 
			@SuppressWarnings("rawtypes") Map options) {
		SootMethod sm = b.getMethod();
		SootClass sc = (sm == null ? null : sm.getDeclaringClass());

		getIntentClass();
		getContentResolverClass();
		ICCLog("** method name of current body"+ b.getMethod().getName());
		ICCLog("** name of the declaring class of current body"+ sc.getName());

		final PatchingChain<Unit> units = b.getUnits();
		for(Iterator<Unit> iter = units.snapshotIterator(); iter.hasNext();) {
			Stmt stmt = (Stmt) iter.next();
			String stmtName = stmt.getClass().getName();
			stmtName = stmtName.substring(stmtName.lastIndexOf(".")+1);
			ICCLog(stmtName+": " + stmt.toString());
			if (!(stmt.containsInvokeExpr()) ){ 

				if (stmt instanceof ReturnStmt &&
						isBinder(((ReturnStmt) stmt).getOp())){
					//return binder
					Value currentBinder = ((ReturnStmt) stmt).getOp();
					ICCLog("return binder being processed");
					//System.err.println("	expr type:" + e.getType().toString());
					String serviceOrigin = "";
					//SootClass 
					for(Local lc:b.getLocals()){
						if(isIntent(lc)){
							ICCLog("	intent name:" + lc.getType().toString());
							intentHelper iH = helpers.get(((RefType)(lc.getType())).getSootClass());
							if(iH!=null){
								if(iH.originClass != ""){
									ICCLog("	origin:" + iH.originClass);
									serviceOrigin = iH.originClass;
								}
							}
						}
					}
					if (serviceOrigin =="")
						continue;
					SootMethod onConnectedMethod = new SootMethod("empty",new ArrayList<Type>(),sc.getType());
					SootClass connectionClass = new SootClass("empty");
					for (SootClass thisClass:Scene.v().getClasses()){
						if(thisClass.getName().startsWith(serviceOrigin)){
							for(SootClass implementedInterface:thisClass.getInterfaces()){
								if(implementedInterface.getName().equals("android.content.ServiceConnection")){
									ICCLog("	class found:" + thisClass.getName());
									onConnectedMethod = thisClass.getMethodByName("onServiceConnected");
									connectionClass = thisClass;
									break;
								}
							}
							
						}
					}
					
					Local myConnectionInstance = Jimple.v().newLocal("Connection_"+ Integer.toString(rnd.nextInt(100000000)), 
							connectionClass.getType());
					b.getLocals().add(myConnectionInstance);
					
					AssignStmt stmt1 = Jimple.v().newAssignStmt(myConnectionInstance, 
							Jimple.v().newNewExpr(connectionClass.getType()));
					SpecialInvokeExpr expr0 = Jimple.v().newSpecialInvokeExpr(
							myConnectionInstance, 
							onConnectedMethod.makeRef(), 
							Arrays.asList(new Value[]{StringConstant.v("none"), currentBinder}));
					InvokeStmt stmt2 = Jimple.v().newInvokeStmt(expr0);
					units.insertBefore(stmt1, stmt);
					units.insertBefore(stmt2, stmt);
					


				}
				continue;
			}

			InvokeExpr e = (InvokeExpr) stmt.getInvokeExpr();
			SootMethod invoke = e.getMethod();


			if (stmt instanceof AssignStmt) {
				// Intent.get*Extra()
				if (e instanceof InstanceInvokeExpr) {
					InstanceInvokeExpr expr = (InstanceInvokeExpr) e;
					Local base = (Local) expr.getBase();
					Type baseType = base.getType();
					Value left = ((AssignStmt) stmt).getLeftOp();
					if (baseType instanceof RefType
							&& isIntent(base)/*(baseType.equals(intentClass.getType())  
                                || ((RefType) baseType).getSootClass().hasSuperclass()
                                    &&((RefType) baseType).getSootClass().getSuperclass().getType().equals(intentClass.getType()))*/
							&& invoke.getName().startsWith("get")
							&& invoke.getName().endsWith("Extra")) {
						SootClass c = getTargetedIntent(sc.getName());
						base.setType(c.getType());
						Value arg0 = expr.getArg(0);
						intentHelper iH = helpers.get(c);
						if(iH==null){
							iH = new intentHelper();
							helpers.put(c, iH);
						}
						if (arg0 instanceof StringConstant) {
							//dbg output
							ICCLog("get Extra being processed");

							String fieldStr = encode(((StringConstant) arg0).value);
							//
							ICCLog("	field name: "+ fieldStr);

							SootField sf = getFieldByName(c, fieldStr);
							if (sf == null) {
								// create field
								ICCLog("	field not exist, creating");

								sf = new SootField(fieldStr, RefType.v("java.lang.Object"), 
										Modifier.STATIC | Modifier.PUBLIC);
								c.addField(sf);
							}
							Stmt newAssignStmt = Jimple.v().newAssignStmt(left, 
									Jimple.v().newStaticFieldRef(sf.makeRef()));
							units.insertBefore(newAssignStmt, stmt);
							units.remove(stmt);
						}
					}




					else if(baseType instanceof RefType
							&&invoke.getName().startsWith("getIntent")){
						//getIntent
						String currentClass = ((RefType)baseType).getSootClass().getType().toString();
						SootClass c = getTargetedIntent(currentClass);
						ICCLog("*****left part type: " + left.getType().toString());
						((Local)left).setType(c.getType());
						if(helpers.get(c)==null){
							intentHelper iH = new intentHelper();
							helpers.put(c, iH);
						}

					}
					/*
					else if(baseType.toString().equals("java.lang.StringBuilder") && invoke.getName().toString().equals("toString")){
						String constString = stringHelpers.get(base);
						if(constString != null){
							ICCLog("	StringBuilder toString" + constString);
//						((AssignStmt)stmt).getLeftOpBox().setValue(StringConstant.v(constString));
							left = StringConstant.v(constString);
//						Stmt constAssignStmt = Jimple.v().newAssignStmt(left,  StringConstant.v(constString));
//						units.insertAfter(constAssignStmt, stmt);
						}
					}*/
				}

				else if(e instanceof StaticInvokeExpr){

					StaticInvokeExpr expr = (StaticInvokeExpr) e;
					Value left = ((AssignStmt) stmt).getLeftOp();
					if(
							invoke.getName().startsWith("parse") &&
							(expr.getArg(0) instanceof StringConstant)){
						//parse uri
						ICCLog("	parse uri being processed");
						Value arg0 = expr.getArg(0);
						String uriString = new String(((StringConstant)arg0).value);
						uriHelpers.put(left, uriString);
						ICCLog("	 uri:" + uriString + uriHelpers.get(left));

					}
				}
			}
			else if(e instanceof InstanceInvokeExpr){

				InstanceInvokeExpr expr = (InstanceInvokeExpr) e;
				Local base = (Local) expr.getBase();
				Type baseType = base.getType();



				// Intent.<init>
				if (invoke.isConstructor() && baseType.equals(intentClass.getType())) {
					int index;

					int i;
					for (i = 0; i < invoke.getParameterCount(); i++) {
						Type t = invoke.getParameterType(i);
						if (t instanceof RefType) {
							//constructor specifies the class that handles the intent, explicit
							if (((RefType) t).getClassName().equals("java.lang.Class")) {
								Value arg = expr.getArg(i);
								if (arg instanceof ClassConstant) {
									SootClass c0 = createIntent(base, false);
									String targetName = ((ClassConstant) arg).getValue();
									//SootClass c = getTargetedIntent(targetName);
									//base.setType(c.getType());
									//added for compatibility with implicit intent
									intentHelper iH = helpers.get(c0);
									intentInfo ifo = new intentInfo(new IntentFilterRecord(), ((RefType)baseType).getSootClass(), targetName);
									iH.infos.add(ifo);

									break;
								}
							}
						}
					}
					if(i==invoke.getParameterCount()){

						//no target class specified
						//extract action and uri here
						//resolving implicit intents and return target classes
						//		
						ICCLog("creating implicit intent");
						SootClass c0 = createIntent(base, true);
						intentHelper iH = helpers.get(c0);
						String actionName, uri;
						switch(expr.getArgCount()){
						case 0:
							ICCLog("empty intent constructor found, doing nothing!");
							break;
						case 1:
							//only action specified
							if(expr.getArg(0) instanceof StringConstant){
								actionName = ((StringConstant) expr.getArg(0)).value;
								intentFilterElimination(iH, actionName, "Action");
							}
							break;
						case 2:
							//action and uri specified
							if(expr.getArg(0) instanceof StringConstant){
								actionName = ((StringConstant) expr.getArg(0)).value;
								uri =  uriHelpers.get( expr.getArg(1));
								intentFilterElimination(iH, actionName, "Action");
								intentFilterElimination(iH, uri, "Data");
							}

							break;
						}
					}
				}
				else if (invoke.isConstructor() && baseType.equals(intentFilterClass.getType())  ){
				// IntentFilter.<init>
					Value dynamicFilterInstance = (Local)base;
					IntentFilterRecord dynamicFilterRecord = new IntentFilterRecord();
					filterHelpers.put(dynamicFilterInstance, dynamicFilterRecord);
					archivedIntentFilters.add(dynamicFilterRecord);

					Value arg0, arg1;
					if(expr.getArgCount()>=1){
						arg0 = expr.getArg(0);
						if(arg0 instanceof StringConstant){
							String actionName = ((StringConstant) arg0).value;
							dynamicFilterRecord.setAction(actionName);

						}
						if(expr.getArgCount()>=2){
							arg1 = expr.getArg(1);
							if(arg1 instanceof StringConstant){
								String dataType = ((StringConstant) arg1).value;
								dynamicFilterRecord.addDataType(dataType);
							}
						}
					}
				}
				/*
				else if (invoke.isConstructor() && baseType.toString().equals("java.lang.StringBuilder")){
					stringHelpers.put(base, "");
					System.err.println("	stringBuilder Init");
				}
				else if (baseType.toString().equals("java.lang.StringBuilder") && invoke.getName().equals("append")){
					Value arg0 = expr.getArg(0);
					if(arg0 instanceof StringConstant){
						String appendedString = ((StringConstant)arg0).value;
						if(stringHelpers.get(base) != null){
							stringHelpers.put(base, stringHelpers.get(base) + appendedString);
						}
					}
				}*/
				else if (baseType instanceof RefType
						&& invoke.getName().startsWith("registerReceiver")){
					//IntentFilter specified by code rather than xml
					Value arg0, arg1;
					if(expr.getArgCount()>=2){
						arg0 = expr.getArg(0);
						arg1 = expr.getArg(1);
						if(filterHelpers.get(arg1)!= null){
							(filterHelpers.get(arg1)).setTarget(arg0.getType().toString());
						}
					}



				}

				// Intent.put*Extra
				else  if (baseType instanceof RefType
						&& isIntent(base)
						&& invoke.getName().startsWith("put")
						&& invoke.getName().endsWith("Extra")) {
					SootClass c = ((RefType) baseType).getSootClass();
					Value arg0 = expr.getArg(0);
					Value arg1 = expr.getArg(1);
					if (arg0 instanceof StringConstant) {
						String fieldStr = encode(((StringConstant) arg0).value);

						SootField sf = getFieldByName(c, fieldStr);
						if (sf == null) {
							// create field
							sf = new SootField(fieldStr, RefType.v("java.lang.Object"), 
									Modifier.STATIC | Modifier.PUBLIC);
							c.addField(sf);
						}
						Stmt assignStmt = Jimple.v().newAssignStmt(Jimple.v().newStaticFieldRef(sf.makeRef()), arg1);
						units.insertBefore(assignStmt, stmt);
						units.remove(stmt);
					}
				}

				else if (baseType instanceof RefType
						&& isIntent((Value)base)
						&& invoke.getName().startsWith("set"))
				{
					Value arg0 = expr.getArg(0);
					SootClass c0 = ((RefType) baseType).getSootClass();
					String opsStr0 = "";
					SootClass uriClass;
					if(arg0 instanceof StringConstant){
						opsStr0 = ((StringConstant) arg0).value;
					}
					if(arg0.getType().toString().equals("null_type")){
						continue;
					}
					intentHelper iH = helpers.get(c0);
					//implicit intent, set* methods
					String methodName = invoke.getName();
					ICCLog(methodName +" being processed");
					methodName = methodName.replaceFirst("set","");
					switch(methodName){
					//implicit, elimination of filters
					case "Data":
					case "DataAndNormalize":
						ICCLog("	arg0 type:"+ arg0.getType().toString());
						uriClass = ((RefType)((Local)arg0).getType()).getSootClass();
						opsStr0 = uriHelpers.get(arg0);
						if(opsStr0 != null){
							ICCLog("	uri:" + opsStr0);
							intentFilterElimination(iH, opsStr0, "Data");
						}

						break;
					case "Action":
					case "Type":
						intentFilterElimination(iH, opsStr0, methodName);
						break;

					case "DataAndType":
					case "DataAndTypeAndNormalize":
						String opsStr1 = ((StringConstant)(expr.getArg(1))).value;
						uriClass = ((RefType)((Local)arg0).getType()).getSootClass();
						opsStr0 = uriHelpers.get(arg0);
						intentFilterElimination(iH, opsStr0, "Data");
						if(opsStr0 != null){
							ICCLog("	uri:" + opsStr0);
							intentFilterElimination(iH, opsStr1, "Type");
						}
						break;
						//explicit, decisions
					case "Class":

						break;
					case "ClassName":
						break;

					case "Component":
						break;
					default:
						break;
					}
				}


				// Intent.setType
				/*else if (baseType instanceof RefType
                            && isIntent(base)
                            && invoke.getName().startsWith("setType")
                            && expr.getArg(0) instanceof StringConstant){


                    SootClass c = ((RefType) baseType).getSootClass();
                    Value arg0 = expr.getArg(0);
                    String typeStr = ((StringConstant) arg0).value;
					intentHelper iH = helpers.get(c);


					for(Iterator<intentInfo> ifoit =iH.infos.iterator();
							ifoit.hasNext();){
						intentInfo ifo = ifoit.next();
						if(ifo.ifr.getDataTypes().size()==0
								||!(ifo.ifr.getDataTypes().contains(typeStr))){
							//type requirement not met
							for(Iterator<Map.Entry<String, SootClass>> it = targetedIntents.entrySet().iterator();
									it.hasNext();){
								Map.Entry<String, SootClass> entry = it.next();
								if(entry.getKey().equals(ifo.ifr.getTarget())
											&& entry.getValue().equals(ifo.targetedClass)){
									it.remove();
									break;
								}
							}
							ifoit.remove();
						}
					}
				}*/

				// startActivity*
				else if (baseType instanceof RefType
						&& expr.getArgCount()>0 
						&& (invoke.getName().startsWith("startActivity")||
								invoke.getName().startsWith("startService")||
								(invoke.getName().startsWith("send")&& invoke.getName().endsWith("Broadcast"))||
								invoke.getName().startsWith("bindService")
								)){

					String currentClass = ((RefType)baseType).getSootClass().getType().toString();
					int i;
					Value arg0 = expr.getArg(0);
					for(i=0;i<expr.getArgCount();i++){
						arg0 = expr.getArg(i);
						if(isIntent(arg0)){
							break;
						}
					}
					if(i==expr.getArgCount()){
						continue;
					}

					ICCLog("arg name:"+ ((Local)arg0).getName());
					ICCLog("arg type:"+ ((Local)arg0).getType().toString());



					SootClass c = ((RefType)(arg0.getType())).getSootClass();
					intentHelper iH = helpers.get(c);
					if(iH == null){
						iH = new intentHelper();
						helpers.put(c, iH);
					}
					iH.originClass = currentClass;
					if( iH.returningIntent != null){
						//for result
						targetedIntents.put(currentClass, iH.returningIntent);
					}
					ICCLog("startActivity being processed");
					ICCLog("stmt string: " + invoke.toString());
					ICCLog("baseType: " + currentClass);
					ICCLog("intent argument type: " + arg0.getType().toString());
					//remove filter records that don't meet category requirements



					if(iH.infos!=null && iH.categories != null ){
						if(iH.infos.size()>0 && iH.categories.size()>0){
							for(intentInfo ifo:iH.infos){
								int counter = 0;
								for(String cate:iH.categories){
									if(ifo.ifr.getCategories().contains(cate)){
										break;
									}
									counter++;
								}
								if(counter == iH.categories.size()){
									//no matching category exists, remove filter
									/*
									for(Iterator<Map.Entry<String,SootClass>> it = targetedIntents.entrySet().iterator();
										it.hasNext();){
										Map.Entry<String, SootClass> entry = it.next();
										if(entry.getKey().equals(ifo.ifr.getTarget())
												&& entry.getValue().equals(ifo.targetedClass)){
											it.remove();
											break;
										}
									}*/
									iH.infos.remove(ifo);
								}
							}
						}
					}	

					//insert Stmt
					//need more detection

					ICCLog("	"+ iH.infos.size() +" component(s) found to handle this intent");
					if(iH.infos.size()==0){
						//no component found
						continue;
					}
					for(intentInfo ifo:(iH.infos)){
						modIntentTarget(c, ifo.targetedClass, base, stmt, units, b);
						targetedIntents.put(ifo.targetedClass, c);

						//units.remove(stmt);
					}
				}








				// Intent.addCategory
				else if (baseType instanceof RefType
						&& ((RefType) baseType).getSootClass().hasSuperclass()
						&& (((RefType) baseType).getSootClass().getSuperclass().getType().equals(intentClass.getType()))
						&& invoke.getName().startsWith("addCategory")
						&& expr.getArg(0) instanceof StringConstant){

					SootClass c = ((RefType) baseType).getSootClass();
					Value arg0 = expr.getArg(0);
					intentHelper iH = helpers.get(c);
					String categoryStr = ((StringConstant) arg0).value;
					for(Iterator<intentInfo> it = iH.infos.iterator();
							it.hasNext();){
						intentInfo ifo = it.next();
						if(!(ifo.ifr.getCategories().contains(categoryStr))){
							it.remove();
							break;
						}
					}

				}

				//setResult
				else if(baseType instanceof RefType
						&& invoke.getName().startsWith("setResult")
						&& expr.getArgCount()>1){

					String currentClass = ((RefType)baseType).getSootClass().getType().toString();
					Value arg0 = expr.getArg(1);
					SootClass incomingIntent = getTargetedIntent(currentClass);
					intentHelper iHincoming = helpers.get(incomingIntent);
					if(!(arg0.getType().toString().equals("null_type"))){
						SootClass returnedIntent = ((RefType)(arg0.getType())).getSootClass();
						if(iHincoming != null){
							String origin = new String(iHincoming.originClass);
							ICCLog("	originClass: " + origin);
							returnedIntent = modIntentTarget(returnedIntent, origin, new JimpleLocal("",returnedIntent.getType()), stmt, units, b);
						}
						else{
							iHincoming = new intentHelper();
							iHincoming.returningIntent = returnedIntent;
						}
						intentHelper iHreturned = helpers.get(returnedIntent);
						iHreturned.originClass = new String(currentClass);
					}


					/*for(intentExtraInfo ieInfo:iHreturned.extraInfos){
						SootField sf = getFieldByName(returnedIntent , ieInfo.fieldStr);
		                if (sf == null) {
        		         // create field
						System.err.println("	extra field not found, creating");
                		sf = new SootField(ieInfo.fieldStr, RefType.v("java.lang.Object"), 
                        		            Modifier.STATIC | Modifier.PUBLIC);
		                returnedIntent.addField(sf);
						}

						System.err.println("	inserting stmt for putExtra");

           		        Stmt assignStmt = 
							Jimple.v().newAssignStmt(
									Jimple.v().newStaticFieldRef(sf.makeRef()),
									ieInfo.argVal);
                        units.insertBefore(assignStmt, stmt);
					}*/
				}



				// Context.startService | Activity.startActivity | and
				// more. Seems not necessary as we have handled
				// put*Extra()
				/*else if ((index = containsTargetedIntentArg(expr)) != -1 ) {
                    Local arg = (Local) expr.getArg(index);
                    Local newArg = Jimple.v().newLocal(arg.getName() + "_intent", NullType.v()); 
                    expr.setArg(index, newArg);
                }*/
				// Replace Handler.sendMessage() with Handler.handleMessage()
				//
				else if ((invoke = getDeclaringMethod(invoke))  != null
						&& invoke.getDeclaringClass().getName().equals("android.os.Handler")
						&& invoke.getName().startsWith("sendMessage")) {
					Value arg0 = expr.getArg(0);
					SootClass baseClass = ((RefType) baseType).getSootClass();
					if (baseClass.declaresMethod("handleMessage", 
							Arrays.asList(new Type[]{RefType.v("android.os.Message")}))) {
						SootMethod handleMethod = baseClass.getMethod("handleMessage", 
								Arrays.asList(new Type[]{RefType.v("android.os.Message")}));
						Stmt newInvokeStmt = Jimple.v().newInvokeStmt(
								Jimple.v().newVirtualInvokeExpr(base, handleMethod.makeRef(), arg0));
						units.insertAfter(newInvokeStmt, stmt);
					}
				}
				// Replace android.os.AsyncTask.execute() with android.os.AsyncTask.doInBackground()
				else if ((invoke = getDeclaringMethod(invoke))  != null
						&& invoke.getDeclaringClass().getName().equals("android.os.AsyncTask")
						&& invoke.getName().equals("execute")) {
					Value arg0 = expr.getArg(0);
					SootClass baseClass = ((RefType) baseType).getSootClass();
					if (baseClass.declaresMethod("doInBackground", 
							Arrays.asList(new Type[]{ArrayType.v(RefType.v("java.lang.Object"), 1)}), 
							RefType.v("java.lang.Object")
							)) {
						SootMethod handleMethod = baseClass.getMethod("doInBackground", 
								Arrays.asList(new Type[]{ArrayType.v(RefType.v("java.lang.Object"), 1)}),
								RefType.v("java.lang.Object")
								);
						Stmt newInvokeStmt = Jimple.v().newInvokeStmt(
								Jimple.v().newVirtualInvokeExpr(base, handleMethod.makeRef(), arg0));
						units.insertAfter(newInvokeStmt, stmt);
					}

				}
			}
			
			 if (	(stmt instanceof AssignStmt || stmt instanceof InvokeStmt)&&
					e instanceof InstanceInvokeExpr){
				InstanceInvokeExpr expr = (InstanceInvokeExpr) e;
				Local base = (Local) expr.getBase();
				Type baseType = base.getType();
				if( baseType.equals(contentResolverClass.getType())){
					//ContentResolver methods
					//need one uri argument, which could only be the first argument
					String uriString;	
					if(expr.getArg(0)!=null){
						uriString = uriHelpers.get(expr.getArg(0));
					}
					else{
						ICCLog("	uri not provided");
						continue;
					}
					if(uriString == null){
						continue;
					}
					ICCLog("processing ContentResolver");
					SootClass fakeIntent = createIntent(Jimple.v().newLocal("", intentClass.getType()), true);
					intentHelper iH = helpers.get(fakeIntent);
					intentFilterElimination(iH, uriString, "Provider");
					ICCLog("	uri String:" + uriString);
					for(intentInfo ifo: iH.infos){

						ICCLog("	Provider Name:" + ifo.ifr.getActionName());
						SootClass myProviderClass = Scene.v().getSootClass(ifo.ifr.getTarget());
						if(myProviderClass == null) continue;
						Local myProviderInstance = Jimple.v().newLocal("Provider_"+ Integer.toString(rnd.nextInt(100000000)), myProviderClass.getType());
						b.getLocals().add(myProviderInstance);
						String myMethodName = invoke.getName();
						SootMethod myMethod = myProviderClass.getMethodByName(myMethodName);
						if(myMethod == null||expr.getArgs().isEmpty()) continue;


						AssignStmt stmt1 = Jimple.v().newAssignStmt(myProviderInstance, Jimple.v().newNewExpr(myProviderClass.getType()));
						SpecialInvokeExpr expr0 = Jimple.v().newSpecialInvokeExpr(myProviderInstance, myMethod.makeRef(), expr.getArgs());


						units.insertBefore(stmt1,stmt);
						if(stmt instanceof AssignStmt){
							AssignStmt stmt2 = Jimple.v().newAssignStmt(((AssignStmt)stmt).getLeftOp(), expr0);
							units.insertBefore(stmt2,stmt);
						ICCLog("	inserting Stmt:");
						ICCLog("	" + stmt2.toString());
						}
						else{
							InvokeStmt stmt2 = Jimple.v().newInvokeStmt(expr0);
							units.insertBefore(stmt2,stmt);
						ICCLog("	inserting Stmt:");
						ICCLog("	" + stmt2.toString());
						}
					}


				}
			 }

		}
	}
}
