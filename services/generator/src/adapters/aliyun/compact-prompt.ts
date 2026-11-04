/** 模型只负责独有逻辑；这些公共接口由 compact-v1 展开成正式运行器协议。 */
export const compactPrompt = `为 Minecraft 创作一件装备，材料和意图是灵感，可自由创造状态字段、算法、条件和动画。只用已提供的宿主能力。正文目标1000–2000 token，勿写框架、防御性样板、测试、schema或SDK实现。
输出格式：两行 --- 包住简短 YAML 声明，后面直接写 JavaScript 函数，不加外层Markdown或JSON。
---
name: 装备名
description: 一两句中文，说明操作、限制与恢复
base: feather
state:
  自取字段名: [初值, 最小值, 最大值]
params:
  自取参数名: 数值
cues:
  自取阶段名: [ongoing, 持续刻数]
  自取结果名: [result, 持续刻数]
  自取失败名: [failure, 持续刻数, actor]
---
function act(g,e) { /* 只写独有规则 */ }
function draw(v) { /* 只写独有绘图公式 */ }
这只是字段格式，不是固定技能。base 限 stick/blaze_rod/iron_sword/iron_pickaxe/feather/amethyst_shard。state 可为空；数值 [初值,min,max]，整数末尾加 int；布尔直接写 true/false；枚举写字符串数组（第一项初值）；支持嵌套对象。参数自由命名，仅声明一次，不要重复写入代码。cue=[preview|ongoing|result|failure,1..1200,actor|nearby?,APPLIED|PROPOSED?]，默认 nearby、APPLIED。

act(g,e) 同步JS，无返回值，直接修改 g.s 中声明的字段。g.p 是只读参数，g.self={ref:'self',position:{worldId,x,y,z},health,sneaking,onGround,velocity:{x,y,z}}，g.tick 当前刻，g.random() 有种子随机。
e.type 为 activate/use/move/hit/timer/tick/receipts；activate只代表拿起装备，不是点击。tick 每刻一次。use 为右键，无松开事件；move 含 from/to；hit 是自己发起的攻击（不是受到攻击），含 healthPoints/target；timer 含 key；receipts 含真实 receipts 数组。g.applied 与 g.proposed 为本次成功施加/提交请求的数量。
通用接口：
g.near(radius<=16,limit<=16) 返回实体快照数组；g.ray(distance<=16) 返回 {entity,position,block} 或 null；g.status(ref) 返回快照或 null；g.block(worldPosition) 返回 {block} 或 null。
g.push(ref,x,y,z) 请求增加速度，向量长度<=1.5；g.hurt(ref,hp<=8) 请求伤害；g.damage(hp<=20) 只能在hit修改本次伤害。目标使用self或当前查询/事件提供的ref，PvP默认关闭。位置不是朝向；可用视线命中点减玩家位置求方向，或设计径向能力。
g.after(key,ticks) 安排1..1200刻后的timer；g.cancel(key)取消；g.cooldown(key,ticks)设置通用冷却（0清除）；g.ready(key)检查；g.remaining(key)剩余刻数。无需写心跳、递减或保存代码。每件最多16个任务/冷却。
g.phase(name,key='main')开始或切换活动，并显示同名cue；g.end(key='main')结束；g.cue(id)单次反馈。cue为result时自动绑定本次对应状态的回执，必须在receipts事件且 g.applied/g.proposed满足时调用。PROPOSED只证明请求提交，不表示实际伤害。
操作可能被拒绝，依赖成功的成本和反馈在receipts中处理。收起物品时暂停计时、清除画面；重新拿起时框架恢复剩余时间、活动与物品状态。不要重复初始化或手写这些流程。

draw(v) 无返回值。v.s 是只读状态，v.p 是参数，v.cue 当前cue名，v.t 已播放刻数。
v.particles(name,n<=64,i=>[相对x,y,z])绘制粒子；允许end_rod/cloud/flame/happy_villager/enchant，每帧合计<=128点。
v.sound(name,pitch=.5..2,volume=0..1)，允许block.amethyst_block.chime/entity.experience_orb.pickup/block.beacon.activate/entity.enderman.teleport；同一cue自动去重。
v.text(短文本)动作栏；v.item(物品名,[相对x,y,z],scale<=2,绕Y旋转弧度)展示实体，每帧<=4个。坐标、key、生命周期由框架补齐。根据cue生成不同的可见几何变化，不能只有文字。
可用辅助函数、循环和Math；无Date/RegExp/eval/Function/Promise/async/网络/文件/Math.random。不用可变全局状态。
平台自动测试站立使用、移动使用、攻击、等待和无目标，并另测操作全被拒绝。请设计短流程可达能力；至少一种路径在240刻内产生真实世界操作。不要输出测试场景。`;
