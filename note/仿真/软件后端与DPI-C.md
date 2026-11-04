# 软件后端与 DPI-C 接入

本文只描述接入路径。它不改 RTL。结论先说：不要把 kunminghuV3 的 Difftest 当成缺失的后端。Difftest 是退休时刻的对照探针。当前缺的是一个每拍调用一次的软件后端，外面用 `AnserCore` 的 AXI master 包起来，SoC 只看见 AXI。

## 要分开的两件事

| | 软件后端 | Difftest |
| --- | --- | --- |
| 谁在执行指令 | C++ 后端就是核的后半段 | RTL 自己执行，软件模型只做对照 |
| 数据方向 | 双向：收指令，回提交和重定向，必要时发访存 | 基本上是 RTL 往外送退休事件 |
| 现在能不能用 | 可以，前端已经留了口 | 不能代替后端。kunminghuV3 的 `difftest/` 子模块也没检出 |

kunminghuV3 里，ROB 对每个提交槽做 `DifftestModule(new DiffInstrCommit(...), delay = 3)`，CSR 再送 `DiffArchEvent`、`DiffCSRState`。`Top.scala` 用 `DifftestModule.top` 或 `Gateway.setConfig` 把这些探针收成 DPI，外面的参考模型逐步对比。`delay` 是为了对齐香山自己的提交流水，不是用来实现执行的。

本地已经有的 DPI 只有 flash 这种功能模型：`DifftestFlash` 是 `ExtModule`，`AXI4Flash` 在读地址握手时拉高 `en`。这是“软件提供存储数据”，不是“软件充当核”。

## 现在的边界

前端子模块在，但没有接成一颗核。

- `Frontend` 的端口目前只有 `resetVector`。它没有例化 FTQ、IFU、IBuffer。
- 指令出口在 `IBuffer.io.deq`：`Vec(DecodeWidth, Valid(InstPacket))`，`DecodeWidth = 4`。每条是 32 位指令、`ftqPtr`、`endOffset`、`isLastInFetchBlock`。反压是 `io.deqReady`。
- 后端入口在 `BackendToFTQIO`。`commit` 是 `Valid(FTQPosition)`，`redirect` 是 `Valid(BackendRedirect)`。`FTQPosition` 是 `ptr + offset`。`BackendRedirect` 还有 `target`、`taken`、`mispredict`。
- FTQ 已经会用 `commit.valid` 把 `popPtr` 到 `commit.ptr` 的项标成 `commit`。这个口没有 `ready`，提交脉冲当拍生效。
- `redirect` 还没有接入。`FTQ.scala` 里 `redirectFlush` 固定是 `false.B`。软件后端就算给出重定向，FTQ 现在也不会冲刷。
- `FTQToBackendIO` 和 `IFUToBackendIO` 是空的。
- `soc/SoC.scala` 写的是 `new AnserCore(params)`，并把 `core.io.axi` 接到 1 主设备的 AXI crossbar，从设备目前只有 `AXI4Error`。
- `ansercore/src/AnserCore.scala` 仍是 `in + 1`，没有 `axi`，也没有参数。这两个文件对不上，SoC 现在不能代表一颗核。
- `rtl-sim/main.cc` 只构造 `VAnserCore` 并 `eval()`，没有时钟翻转，也没有 DPI。

所以第一刀不是引入 Difftest 框架，而是把已有的 `InstPacket` 和 `BackendToFTQIO` 接到一个软件后端上。

## 推荐接法

SoC 侧保持现在的形状：只有一个 AXI master。软件后端不要直接碰 UART、flash 或其它从设备。

```text
SoC
  AXI4Xbar
    AnserCore.io.axi
      Frontend RTL
        IBuffer.deq  ------>  SoftwareBackendBridge
        FTQ.commit   <------  每拍一个 DPI 调用
        FTQ.redirect <------
      AXI 适配
        取指 miss、load、store、MMIO 都变成 AXI
```

DPI 只放在桥里，不放进 BPU、FTQ、IFU。Chisel 侧用一个 `ExtModule`，Verilog 里写死一次 `import "DPI-C"`，C 符号自己定，例如 `anser_backend_step`。不要依赖 Verilator 给 `ExtModule` 端口自动生成的符号，也不要先把 OpenXiangShan/difftest 整套 gateway 搬进来。

调用契约：

- 每个上升沿、复位释放后调用一次。输出在 Chisel 里寄存一拍。软件模型因此有明确的拍边界。
- 输入是最多 4 条 `InstPacket` 的 valid，加上 AXI 读回的数据。输出是本拍能接收几条、0 或 1 次提交、0 或 1 次重定向、0 或 1 次访存请求。
- 提交沿用现有的 `Valid`，没有反压。桥必须保证 FTQ 来得及消费；在 FTQ 真正处理重定向之前，C++ 不要提前发下一条依赖新 PC 的指令流。
- `DecodeWidth = 4` 只是上限。第一版 C++ 每拍只收 1 条、只提交 1 条。`deqReady` 由桥按后端是否还忙来拉低。
- 复位期间不调用 step。镜像加载用单独的 `anser_backend_init`，在放开复位前由仿真 `main` 调用。

访存分两种，不要同时生效。

- 前期闭环：C++ 自己有内存。取指也可以暂时走 DPI 读。这能让前端和提交指针先跑起来，但 SoC 上的设备对 ISS 不可见。
- SoC 仿真：程序放在 AXI 从设备里。取指 miss、load、store、MMIO 都由桥变成 `AXI4MasterIO` 事务，完成后再在后续的 `step` 里把数据交回 C++。DPI 函数本身不再读这份内存。

`AXI4Flash` 的时序不要照搬到有状态的 ISS 上。flash 是 `en/addr` 进去、`data` 出来，延迟来自 AXI 适配器的状态机。ISS 有寄存器、异常和未完成访存，必须用“一拍一次 step + 寄存输出”。

## 落地顺序

每一步都能单独生成 Verilog 或跑一个小仿真，再做下一步。

1. 把 `AnserCore` 改成 SoC 正在例化的那颗核：构造参数是 `AXI4Params`，唯一对外端口是 `AXI4MasterIO`。后端先是空的，不发事务。确认 `SoC` 能生成 Verilog。这一步只消除现在的接口错位。
2. 加 `SoftwareBackendBridge`。Verilog 黑盒在 `posedge clock && !reset` 调用 `anser_backend_step`。C++ 先做空模型：永远 `deqReady`，不提交、不重定向、不访存。Verilator 测试台要补上时钟翻转和复位。
3. 把 `IBuffer.io.deq` 接到桥，把桥的提交接到 `BackendToFTQIO.commit`。C++ 改成按顺序收回指令并提交对应的 `FTQPosition`。此阶段仍然不发重定向。通过标准是 FTQ 项能从 `waitCommit` 进入 `commit`。
4. 在 FTQ 里把 `redirectFlush` 接到 `BackendRedirect`，按 `redirectPosition` 冲刷，而不是继续写死 `false.B`。C++ 再开始对分支返回 `target/taken/mispredict`。这一步之前，重定向只存在于 Bundle 定义里。
5. 把桥的访存请求接成 AXI master，接到 `AnserCore.io.axi`。取指 miss 和 load/store 都走这条 AXI。SoC 的 crossbar 后面先放一个 DPI 或 C++ 内存从设备，替换现在的全地址 `AXI4Error`。UART 仍然挂在 crossbar 上，由 ISS 的 MMIO 访问到达，而不是由 DPI 直接写 UART。
6. 提交记录稳定以后，再加一个只出不进的观察口，字段对齐香山 `DiffInstrCommit` 的最小子集：`valid`、`pc`、`instr`、写寄存器使能和写数据。它可以调用第二个 ISS 或 NEMU。这才是 Difftest 的位置。不需要为了这个去实例化 `DiffCSRState`、`DiffLoadEvent` 那一整套。

第 6 步不阻塞第 5 步。SoC 仿真要的是第 5 步。

## 不要做的事

- 不要在 BPU、IFU、FTQ 内部散落 DPI 调用。预测器训练仍由 FTQ 的 `toBPU.train` 在 RTL 内完成，软件后端只提供提交和重定向。
- 不要让 ISS 和 RTL 各持有一份会被 SoC 设备修改的内存。第 5 步之后只有 AXI 从设备持有这份内存。
- 不要把香山 gateway 的 `delay = 3` 用到功能后端上。那是探针对齐，不是执行延迟。
- 不要在 `difftest/` 子模块还没检出、后端还没执行指令时，把 kunminghuV3 的 `HasDiffTestInterfaces` 接到 `AnserCore` 顶层。

## 目录分级

只加三处。Chisel 仍放在 `ansercore/src`，C++ 不放进这个目录，ISS 也不拷进仓库。

```text
ansercore/src/
  AnserCore.scala                 # 核顶：例化前端和桥，只导出 AXI master
  frontend/                       # 已有 RTL 前端
  mmu/                            # 已有
  device/axi/                     # 已有 AXI、crossbar、flash、uart
  soc/SoC.scala                   # 已有 SoC 顶
  backend/software/               # 新增，package ansercore.backend.software
    BackendBundles.scala          # 每拍传给 DPI 的固定字段
    SoftwareBackend.scala         # ExtModule，Verilog 里 import DPI-C
    SoftwareBackendBridge.scala   # InstPacket、FTQ、AXI 和 DPI 之间的适配

rtl-sim/
  main.cc                         # 时钟、复位、调用 anser_backend_init
  dpi/
    anser_backend.h               # 稳定的 C ABI
    anser_backend.cc              # step：转调外面的 ISS
```

对应关系：

- `frontend/` 只保留取指和预测。它不出现 DPI。
- `backend/software/` 是暂时的后端。以后换成 RTL 后端时，删除这一目录，`AnserCore.scala` 改例化新后端，SoC 不用动。
- `device/axi/` 是 SoC 上的从设备，不是核的一部分。`DifftestFlash` 继续留在这里，不放到 `backend/`。
- `rtl-sim/dpi/` 只放桥的 C 符号。ISS 仍在仓库外的 `/home/yang/work/ISS`，由 `anser_backend.cc` 链接，不把 ISS 源码嵌进来。
- 说明继续放 `note/仿真/`。它不是可编译源码。

`ansercore/src` 下面现在的包名并不都等于目录名，例如 `util/` 的包是 `utils`，`soc/` 的包是 `soc`。新目录按目录使用 `package ansercore.backend.software`，不再沿用这种错位。

## 源码

- 前端顶层：`ansercore/src/frontend/Frontend.scala`
- 指令包：`ansercore/src/frontend/ifu/ibuffer/IBuffer.scala`，`IBufferBundles.scala`
- 提交与重定向：`ansercore/src/frontend/FrontendBundles.scala`，`ansercore/src/CoreBundles.scala`，`ansercore/src/frontend/ftq/FTQ.scala`
- 核与 SoC：`ansercore/src/AnserCore.scala`，`ansercore/src/soc/SoC.scala`
- 现有 DPI 黑盒：`ansercore/src/device/axi/common/DifftestFlash.scala`，`ansercore/src/device/axi/AXI4Flash.scala`
- 仿真入口：`rtl-sim/main.cc`
- 香山对照：`kunminghuV3/src/main/scala/xiangshan/backend/rob/Rob.scala` 的 `DiffInstrCommit`，`kunminghuV3/src/main/scala/top/Top.scala` 的 `DifftestModule.top`。其 `difftest/` 目录是空的子模块，框架实现本身不在当前检出里。
