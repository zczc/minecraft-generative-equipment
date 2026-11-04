/* Java 和 QuickJS 之间只传 UTF-8 JSON 与本地编译产物。每次调用独立 runtime，
 * 配额覆盖模块初始化、查询、处理器与 JSON 序列化；任何异常都丢弃输出。 */
#include <jni.h>
#include "quickjs.h"
#include <string.h>
#include <time.h>
#include <stdlib.h>
#include <stdint.h>
#include <math.h>

typedef struct { JNIEnv *env; jobject bridge; int queries, limit; int64_t deadline; int output_nodes; } Scope;
static int64_t now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return (int64_t)t.tv_sec*1000000000+t.tv_nsec; }
static int interrupt(JSRuntime *rt, void *opaque) { (void)rt; return now_ns() >= ((Scope*)opaque)->deadline; }
static void fail(JNIEnv *env, const char *message) { (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalArgumentException"),message); }
static void js_error(JNIEnv *env, JSContext *ctx) {
    JSValue ex=JS_GetException(ctx); const char *msg=JS_ToCString(ctx,ex);
    fail(env,msg?msg:"QuickJS resource limit or exception"); if(msg)JS_FreeCString(ctx,msg); JS_FreeValue(ctx,ex);
}
static char *utf8(JNIEnv *env,jbyteArray data,jsize *len) {
    *len=(*env)->GetArrayLength(env,data); char *p=malloc((size_t)*len+1); if(!p){fail(env,"Allocation failed");return NULL;}
    (*env)->GetByteArrayRegion(env,data,0,*len,(jbyte*)p);p[*len]=0;return p;
}
static jbyteArray bytes(JNIEnv *env,const void *p,size_t n) {
    if(n>1048576){fail(env,"Output exceeds one MiB");return NULL;}
    jbyteArray a=(*env)->NewByteArray(env,(jsize)n);if(a)(*env)->SetByteArrayRegion(env,a,0,(jsize)n,p);return a;
}
static JSValue query(JSContext *ctx,JSValueConst self,int argc,JSValueConst *argv) {
    (void)self;Scope *s=JS_GetContextOpaque(ctx);
    if(!s->bridge || argc!=2 || ++s->queries>s->limit) return JS_ThrowInternalError(ctx,"Query not allowed or budget exceeded");
    JSValue args=JS_JSONStringify(ctx,argv[1],JS_UNDEFINED,JS_UNDEFINED);
    if(JS_IsException(args))return args;
    size_t nn,an; const char *n=JS_ToCStringLen(ctx,&nn,argv[0]),*a=JS_ToCStringLen(ctx,&an,args);
    if(!n||!a||nn>128||an>65536){if(n)JS_FreeCString(ctx,n);if(a)JS_FreeCString(ctx,a);JS_FreeValue(ctx,args);return JS_ThrowTypeError(ctx,"Invalid query input");}
    JNIEnv *env=s->env;jbyteArray name=bytes(env,n,nn),json=bytes(env,a,an);
    jclass cls=(*env)->GetObjectClass(env,s->bridge);jmethodID method=(*env)->GetMethodID(env,cls,"query","([B[B)[B");
    jbyteArray response=(jbyteArray)(*env)->CallObjectMethod(env,s->bridge,method,name,json);
    JS_FreeCString(ctx,n);JS_FreeCString(ctx,a);JS_FreeValue(ctx,args);
    (*env)->DeleteLocalRef(env,name);(*env)->DeleteLocalRef(env,json);(*env)->DeleteLocalRef(env,cls);
    if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);return JS_ThrowInternalError(ctx,"Host query failed");}
    if(!response)return JS_ThrowInternalError(ctx,"Missing query result");
    jsize len;char *text=utf8(env,response,&len);JSValue result=JS_ParseJSON(ctx,text,len,"query.json");free(text);(*env)->DeleteLocalRef(env,response);return result;
}
/* JSON.stringify 默认把 NaN 变成 null、丢弃 undefined；这里拒绝这种无声改写。 */
static JSValue checked_json(JSContext *ctx,JSValueConst self,int argc,JSValueConst *argv) {
    (void)self; Scope *s=JS_GetContextOpaque(ctx);
    if(argc<2||++s->output_nodes>65536)return JS_ThrowTypeError(ctx,"Output node limit exceeded");
    JSValueConst v=argv[1];double number;
    if(JS_IsNumber(v)){if(JS_ToFloat64(ctx,&number,v)<0||!isfinite(number))return JS_ThrowTypeError(ctx,"Non-finite output");}
    else if(!JS_IsNull(v)&&!JS_IsBool(v)&&!JS_IsString(v)&&!JS_IsObject(v))return JS_ThrowTypeError(ctx,"Non-JSON output");
    if(JS_IsFunction(ctx,v))return JS_ThrowTypeError(ctx,"Function in output");
    return JS_DupValue(ctx,v);
}
static JSModuleDef *loader(JSContext *ctx,const char *name,void *opaque) {
    (void)opaque;
    if(strcmp(name,"@ge/sdk")){JS_ThrowReferenceError(ctx,"Module unavailable");return NULL;}
    const char *sdk="export function lerp(a,b,t){return a+(b-a)*t} export function clamp(x,a,b){return Math.max(a,Math.min(b,x))}";
    JSValue m=JS_Eval(ctx,sdk,strlen(sdk),name,JS_EVAL_TYPE_MODULE|JS_EVAL_FLAG_COMPILE_ONLY);
    if(JS_IsException(m))return NULL;JSModuleDef *def=JS_VALUE_GET_PTR(m);JS_FreeValue(ctx,m);return def;
}
static JSContext *context(JSRuntime **rt,Scope *s,jlong memory) {
    *rt=JS_NewRuntime();if(!*rt)return NULL;JS_SetMemoryLimit(*rt,(size_t)memory);JS_SetMaxStackSize(*rt,512*1024);
    JS_SetInterruptHandler(*rt,interrupt,s);JS_SetModuleLoaderFunc(*rt,NULL,loader,NULL);
    // 不安装文件或网络模块；Promise 内部用于模块求值，但其全局入口关闭。
    JSContext *ctx=JS_NewContextRaw(*rt);if(!ctx)return NULL;
    JS_AddIntrinsicBaseObjects(ctx);JS_AddIntrinsicEval(ctx);JS_AddIntrinsicJSON(ctx);JS_AddIntrinsicMapSet(ctx);JS_AddIntrinsicPromise(ctx);
    JS_SetContextOpaque(ctx,s);
    const char *setup="globalThis.Promise=undefined;globalThis.Date=undefined;globalThis.RegExp=undefined;globalThis.eval=undefined;globalThis.Function=undefined;Math.random=undefined;Object.defineProperty((function(){}).constructor.prototype,'constructor',{value:undefined});";
    // Function 已被移除，先用函数原型阻断构造器后再运行不可信模块。
    JSValue v=JS_Eval(ctx,setup,strlen(setup),"host.js",JS_EVAL_TYPE_GLOBAL);if(JS_IsException(v)){JS_FreeValue(ctx,v);JS_FreeContext(ctx);return NULL;}JS_FreeValue(ctx,v);
    return ctx;
}
JNIEXPORT jbyteArray JNICALL Java_dev_generative_equipment_runtime_NativeBridge_compile(JNIEnv *env,jclass cls,jbyteArray source) {
    (void)cls;Scope s={env,NULL,0,0,now_ns()+2000000000LL};JSRuntime *rt=NULL;JSContext *ctx=context(&rt,&s,32*1024*1024);jbyteArray out=NULL;
    if(!ctx){fail(env,"QuickJS initialization failed");goto end;}
    jsize len;char *code=utf8(env,source,&len);if(!code)goto cleanup;
    JSValue module=JS_Eval(ctx,code,len,"behavior.js",JS_EVAL_TYPE_MODULE|JS_EVAL_FLAG_COMPILE_ONLY);free(code);
    if(JS_IsException(module)){js_error(env,ctx);goto cleanup;}
    size_t size;uint8_t *buf=JS_WriteObject(ctx,&size,module,JS_WRITE_OBJ_BYTECODE);JS_FreeValue(ctx,module);
    if(!buf){js_error(env,ctx);goto cleanup;}out=bytes(env,buf,size);js_free(ctx,buf);
cleanup:JS_FreeContext(ctx);
end:if(rt)JS_FreeRuntime(rt);return out;
}
JNIEXPORT jbyteArray JNICALL Java_dev_generative_equipment_runtime_NativeBridge_execute(JNIEnv *env,jclass cls,jbyteArray bytecode,jbyteArray input,jbyteArray event,jboolean render,jobject bridge,jlong memory,jlong duration,jint limit) {
    (void)cls;Scope s={env,bridge,0,limit,now_ns()+duration};JSRuntime *rt=NULL;JSContext *ctx=context(&rt,&s,memory);jbyteArray out=NULL;
    if(!ctx){fail(env,"QuickJS initialization failed");goto end;}
    jsize size;char *bc=utf8(env,bytecode,&size);if(!bc)goto cleanup;
    JSValue mod=JS_ReadObject(ctx,(uint8_t*)bc,size,JS_READ_OBJ_BYTECODE);free(bc);
    if(JS_IsException(mod)){js_error(env,ctx);goto cleanup;}
    JSModuleDef *def=JS_VALUE_GET_PTR(mod);
    if(JS_ResolveModule(ctx,mod)<0){JS_FreeValue(ctx,mod);js_error(env,ctx);goto cleanup;}
    JSValue eval=JS_EvalFunction(ctx,mod);if(JS_IsException(eval)){js_error(env,ctx);goto cleanup;}
    if(JS_PromiseState(ctx,eval)==JS_PROMISE_REJECTED){JSValue err=JS_PromiseResult(ctx,eval);JS_Throw(ctx,err);JS_FreeValue(ctx,eval);js_error(env,ctx);goto cleanup;}
    if(JS_PromiseState(ctx,eval)==JS_PROMISE_PENDING){JS_FreeValue(ctx,eval);fail(env,"Async module not allowed");goto cleanup;}JS_FreeValue(ctx,eval);
    JSValue ns=JS_GetModuleNamespace(ctx,def);JSValue fn=JS_GetPropertyStr(ctx,ns,render?"render":"onEvent");JS_FreeValue(ctx,ns);
    if(!JS_IsFunction(ctx,fn)){JS_FreeValue(ctx,fn);fail(env,"Missing synchronous handler");goto cleanup;}
    jsize il,el;char *it=utf8(env,input,&il),*et=utf8(env,event,&el);
    JSValue args[2]={JS_ParseJSON(ctx,it,il,"context.json"),JS_ParseJSON(ctx,et,el,"event.json")};free(it);free(et);
    if(JS_IsException(args[0])||JS_IsException(args[1])){JS_FreeValue(ctx,args[0]);JS_FreeValue(ctx,args[1]);JS_FreeValue(ctx,fn);js_error(env,ctx);goto cleanup;}
    if(!render){JS_SetPropertyStr(ctx,args[0],"query",JS_NewCFunction(ctx,query,"query",2));}
    // 随机种子由宿主写入；随机入口只属于行为上下文，不放进全局 Math。
    JSValue global=JS_GetGlobalObject(ctx);JS_SetPropertyStr(ctx,global,"__input",JS_DupValue(ctx,args[0]));
    const char *freeze="(()=>{const input=globalThis.__input;delete globalThis.__input;if(input.seed!==undefined){let x=input.seed|0;input.random=()=>{x^=x<<13;x^=x>>>17;x^=x<<5;return (x>>>0)/4294967296};delete input.seed;}const freeze=v=>{if(v&&typeof v==='object'){Object.values(v).forEach(freeze);Object.freeze(v)}};freeze(input)})()";
    JSValue f=JS_Eval(ctx,freeze,strlen(freeze),"input.js",JS_EVAL_TYPE_GLOBAL);JS_FreeValue(ctx,global);
    if(JS_IsException(f)){JS_FreeValue(ctx,f);JS_FreeValue(ctx,fn);JS_FreeValue(ctx,args[0]);JS_FreeValue(ctx,args[1]);js_error(env,ctx);goto cleanup;}JS_FreeValue(ctx,f);
    JSValue result=JS_Call(ctx,fn,JS_UNDEFINED,2,args);JS_FreeValue(ctx,fn);JS_FreeValue(ctx,args[0]);JS_FreeValue(ctx,args[1]);
    if(JS_IsException(result)){js_error(env,ctx);goto cleanup;}
    if((int)JS_PromiseState(ctx,result)>=0){JS_FreeValue(ctx,result);fail(env,"Async handler not allowed");goto cleanup;}
    JSValue replacer=JS_NewCFunction(ctx,checked_json,"validateJSON",2);
    JSValue json=JS_JSONStringify(ctx,result,replacer,JS_UNDEFINED);JS_FreeValue(ctx,replacer);JS_FreeValue(ctx,result);
    if(JS_IsException(json)){js_error(env,ctx);goto cleanup;}
    size_t n;const char *text=JS_ToCStringLen(ctx,&n,json);
    if(!text||now_ns()>=s.deadline){fail(env,"Execution deadline exceeded");}else out=bytes(env,text,n);
    if(text)JS_FreeCString(ctx,text);JS_FreeValue(ctx,json);
cleanup:JS_FreeContext(ctx);
end:if(rt)JS_FreeRuntime(rt);return out;
}
