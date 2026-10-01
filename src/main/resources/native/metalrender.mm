#define GL_SILENCE_DEPRECATION
#import <AppKit/NSWorkspace.h>
#import <Foundation/NSProcessInfo.h>
#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>
#import <MetalKit/MetalKit.h>
#ifdef METALRENDER_HAS_METALFX
#import <MetalFX/MetalFX.h>
#endif
#import <OpenGL/CGLIOSurface.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl3.h>
#import <QuartzCore/QuartzCore.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <dispatch/dispatch.h>
#include <dlfcn.h>
#include <jni.h>
#include <limits>
#include <mach/mach.h>
#include <mach/mach_host.h>
#include <mach/mach_time.h>
#include <memory>
#include <mutex>
#include <new>
#include <pthread/qos.h>
#include <shared_mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#ifdef __aarch64__
#include <arm_neon.h>
#endif
static FILE *g_debugFile = nullptr;
static void dbg(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void dbg(const char *fmt, ...) {
#ifdef METALRENDER_DEBUG
  if (!g_debugFile) {
    g_debugFile = fopen("/tmp/metalrender_debug.log", "w");
    if (!g_debugFile)
      return;
  }
  va_list args;
  va_start(args, fmt);
  vfprintf(g_debugFile, fmt, args);
  va_end(args);
  static int s_dbgFlushCounter = 0;
  if (++s_dbgFlushCounter >= 120) {
    fflush(g_debugFile);
    s_dbgFlushCounter = 0;
  }
#else
  (void)fmt;
#endif
}
static mach_timebase_info_data_t g_cachedTimebase = {0, 0};
static void ensureTimebase() {
  if (g_cachedTimebase.denom == 0)
    mach_timebase_info(&g_cachedTimebase);
}
static id<MTLBuffer> get_buffer(uint64_t h);
struct ResolvedBuf {
  id<MTLBuffer> buf;
  size_t offset;
};
static ResolvedBuf resolve_buffer(uint64_t h);
#ifndef dispatch_get_active_cpu_count
static inline int dispatch_get_active_cpu_count() {
  unsigned hc = std::thread::hardware_concurrency();
  return (int)(hc == 0 ? 1 : hc);
}
#endif
static bool g_available = true;
id<MTLDevice> g_device = nil;
id<MTLCommandQueue> g_queue = nil;
static std::mutex g_textureUploadMutex;
static id<MTLCommandBuffer> g_lastTextureUploadCommandBuffer = nil;
static std::atomic<bool> g_metal4Requested{false};
static std::atomic<bool> g_metal4Supported{false};
static std::atomic<bool> g_metal4ScaffoldActive{false};
static std::atomic<bool> g_metal4RuntimeVerified{false};
static std::atomic<bool> g_metal4ProbeAttempted{false};
static std::atomic<bool> g_metal4ProbeInProgress{false};
static std::atomic<bool> g_metal4DrawPathActive{false};
static std::atomic<uint64_t> g_systemSleepCount{0};
static std::atomic<uint64_t> g_systemWakeCount{0};
static id g_systemWillSleepObserver = nil;
static id g_systemDidWakeObserver = nil;
// Keep long-lived references dynamically typed so merely loading the dylib on
// macOS 14/15 cannot require a Metal 4 protocol. They are assigned and used as
// typed MTL4 objects only inside explicit macOS 26 availability guards.
static id g_metal4CommandQueue = nil;
static id g_metal4CommandAllocator = nil;
static std::mutex g_metal4Mutex;
static std::atomic<bool> g_tripleBufferingEnabled{true};
static std::atomic<int> g_activeSurfaceSlots{3};
static std::unordered_map<uint64_t, id<MTLBuffer>> g_buffers;
static std::unordered_map<uint64_t, size_t> g_bufferSizes;
static size_t g_individualBufferBytes = 0;
static uint64_t g_nextHandle = 1;
static id<MTLBuffer> g_megaVB = nil;

// A fixed 3 GiB shared allocation is especially expensive on unified-memory
// Macs. Keep the fast suballocator bounded and fall back to individual buffers
// after it fills. The runtime budget is derived from Metal's recommended
// working-set size and can be tightened by nConfigureRuntime.
static constexpr size_t kMiB = 1024ULL * 1024ULL;
static constexpr size_t kMegaVBMinimum = 128ULL * kMiB;
static constexpr size_t kMegaVBDefaultMaximum = 256ULL * kMiB;
static constexpr size_t kMegaVBHardMaximum = 2048ULL * kMiB;
static size_t g_requestedMemoryBudgetBytes = 0;
static size_t g_megaVBCapacity = 0;
static size_t g_megaVBBudget = 0;
static size_t g_megaVBHead = 0;
struct MegaSubAlloc {
  size_t offset;
  size_t size;
};
static std::unordered_map<uint64_t, MegaSubAlloc> g_megaAllocs;

static std::vector<MegaSubAlloc> g_megaFreeList;
static uint64_t g_nextMegaHandle = 0x8000000000000000ULL;
static std::shared_mutex g_megaMutex;

static void megaCoalesceFreeList() {
  if (g_megaFreeList.size() < 2)
    return;

  std::sort(g_megaFreeList.begin(), g_megaFreeList.end(),
            [](const MegaSubAlloc &a, const MegaSubAlloc &b) {
              return a.offset < b.offset;
            });

  size_t write = 0;
  for (size_t read = 1; read < g_megaFreeList.size(); read++) {
    if (g_megaFreeList[write].offset + g_megaFreeList[write].size ==
        g_megaFreeList[read].offset) {
      g_megaFreeList[write].size += g_megaFreeList[read].size;
    } else {
      write++;
      if (write != read)
        g_megaFreeList[write] = g_megaFreeList[read];
    }
  }
  g_megaFreeList.resize(write + 1);
}

static void megaTrimFreeTail() {
  megaCoalesceFreeList();
  for (size_t i = 0; i < g_megaFreeList.size(); i++) {
    MegaSubAlloc &freeBlock = g_megaFreeList[i];
    if (freeBlock.offset + freeBlock.size == g_megaVBHead) {
      g_megaVBHead = freeBlock.offset;
      g_megaFreeList[i] = g_megaFreeList.back();
      g_megaFreeList.pop_back();
      return;
    }
  }
}
struct DeferredDeletion {
  uint64_t handle;
  uint64_t retireAfterSubmission;
  bool isMega;
};
static std::vector<DeferredDeletion> g_deferredDeletions;
static std::mutex g_deferredMutex;
static constexpr int kTripleBufferCount = 3;
static constexpr uint64_t kDeferredSubmissionDelay =
    kTripleBufferCount + 1;
static std::atomic<uint64_t> g_submittedFrameSerial{0};
static std::atomic<uint64_t> g_completedFrameSerial{0};
static std::atomic<bool> g_gpuNeedsRecovery{false};
// Process-lifetime monotonic release-QA telemetry. These counters must not be
// reset by world/renderer lifecycle cleanup: a late asynchronous GPU failure
// must remain observable until the client process exits.
static std::atomic<uint64_t> g_gpuCommandBufferErrorCount{0};
static std::atomic<uint64_t> g_inFlightFrameTimeoutCount{0};
static std::atomic<uint64_t> g_noIOSurfaceSlotSkipCount{0};

// Validation-only Iris MSL compiler state. The result counters are
// process-lifetime monotonic telemetry; liveLibraryCount is a gauge and must
// return to zero before each synchronous JNI call completes.
static constexpr jint kIrisMslCompileFailed = -1;
static constexpr jint kIrisMslCompileUnsupported = 0;
static constexpr jint kIrisMslCompileCompiled = 1;
static constexpr jint kIrisMslCompileDeferred = 2;
static constexpr jsize kIrisMslMaximumSourceBytes =
    16 * 1024 * 1024;
static std::atomic<bool> g_irisMslCompilerReady{false};
static std::mutex g_irisMslCompileMutex;
static std::atomic<uint64_t> g_irisMslCompileAttemptCount{0};
static std::atomic<uint64_t> g_irisMslCompileSuccessCount{0};
static std::atomic<uint64_t> g_irisMslCompileUnsupportedCount{0};
static std::atomic<uint64_t> g_irisMslCompileFailureCount{0};
static std::atomic<uint64_t> g_irisMslLiveLibraryCount{0};

static jint complete_iris_msl_validation(jint result, const char *reason,
                                         NSError *error,
                                         const char *fallbackDomain) {
  if (result == kIrisMslCompileCompiled) {
    g_irisMslCompileSuccessCount.fetch_add(1, std::memory_order_relaxed);
    return result;
  }
  if (result == kIrisMslCompileUnsupported) {
    g_irisMslCompileUnsupportedCount.fetch_add(1,
                                               std::memory_order_relaxed);
    return result;
  }
  if (result == kIrisMslCompileDeferred)
    return result;

  uint64_t failureNumber =
      g_irisMslCompileFailureCount.fetch_add(1, std::memory_order_relaxed) + 1;
  if (failureNumber <= 8 || (failureNumber % 128) == 0) {
    const char *domain = fallbackDomain ? fallbackDomain : "none";
    long code = 0;
    if (error) {
      const char *errorDomain = [[error domain] UTF8String];
      if (errorDomain && errorDomain[0] != '\0')
        domain = errorDomain;
      code = (long)[error code];
    }
    fprintf(stderr,
            "[MetalRender] WARN: IRIS_MSL_VALIDATE result=FAILED "
            "reason=%s domain=%s code=%ld\n",
            reason ? reason : "UNKNOWN", domain, code);
    fflush(stderr);
  }
  return kIrisMslCompileFailed;
}

static void set_iris_msl_compiler_ready(bool ready) {
  std::lock_guard<std::mutex> lock(g_irisMslCompileMutex);
  g_irisMslCompilerReady.store(ready, std::memory_order_release);
}

static void mark_frame_submission_completed(uint64_t serial) {
  uint64_t observed =
      g_completedFrameSerial.load(std::memory_order_acquire);
  while (observed < serial &&
         !g_completedFrameSerial.compare_exchange_weak(
             observed, serial, std::memory_order_release,
             std::memory_order_acquire)) {
  }
}

static inline bool isMegaHandle(uint64_t h) {
  return (h & 0x8000000000000000ULL) != 0;
}
static uint64_t megaAlloc(size_t size) {
  std::unique_lock<std::shared_mutex> lock(g_megaMutex);
  size_t aligned = (size + 255) & ~255;

  int bestIdx = -1;
  size_t bestSize = SIZE_MAX;
  for (int i = 0; i < (int)g_megaFreeList.size(); i++) {
    if (g_megaFreeList[i].size >= aligned &&
        g_megaFreeList[i].size < bestSize) {
      bestIdx = i;
      bestSize = g_megaFreeList[i].size;
      if (bestSize == aligned)
        break;
    }
  }
  if (bestIdx >= 0) {
    size_t offset = g_megaFreeList[bestIdx].offset;
    size_t blockSize = g_megaFreeList[bestIdx].size;

    g_megaFreeList[bestIdx] = g_megaFreeList.back();
    g_megaFreeList.pop_back();
    uint64_t handle = g_nextMegaHandle++;
    g_megaAllocs[handle] = {offset, aligned};
    if (blockSize > aligned) {
      g_megaFreeList.push_back({offset + aligned, blockSize - aligned});
    }
    return handle;
  }
  if (g_megaVBHead + aligned > g_megaVBCapacity) {

    megaCoalesceFreeList();

    bestIdx = -1;
    bestSize = SIZE_MAX;
    for (int i = 0; i < (int)g_megaFreeList.size(); i++) {
      if (g_megaFreeList[i].size >= aligned &&
          g_megaFreeList[i].size < bestSize) {
        bestIdx = i;
        bestSize = g_megaFreeList[i].size;
        if (bestSize == aligned)
          break;
      }
    }
    if (bestIdx >= 0) {
      size_t offset2 = g_megaFreeList[bestIdx].offset;
      size_t blockSize2 = g_megaFreeList[bestIdx].size;
      g_megaFreeList[bestIdx] = g_megaFreeList.back();
      g_megaFreeList.pop_back();
      uint64_t handle2 = g_nextMegaHandle++;
      g_megaAllocs[handle2] = {offset2, aligned};
      if (blockSize2 > aligned) {
        g_megaFreeList.push_back({offset2 + aligned, blockSize2 - aligned});
      }
      return handle2;
    }
    static int megaFailCount = 0;
    if (megaFailCount++ < 10 || megaFailCount % 500 == 0)
      dbg("megaAlloc FAIL: need %zu, head=%zu, cap=%zu, freeBlocks=%zu (fail "
          "#%d)\n",
          aligned, g_megaVBHead, g_megaVBCapacity, g_megaFreeList.size(),
          megaFailCount);
    return 0;
  }
  size_t offset = g_megaVBHead;
  g_megaVBHead += aligned;
  uint64_t handle = g_nextMegaHandle++;
  g_megaAllocs[handle] = {offset, aligned};
  return handle;
}
static void megaFree(uint64_t handle) {
  std::unique_lock<std::shared_mutex> lock(g_megaMutex);
  auto it = g_megaAllocs.find(handle);
  if (it == g_megaAllocs.end())
    return;
  g_megaFreeList.push_back(it->second);
  g_megaAllocs.erase(it);

  size_t freeBytes = 0;
  for (const MegaSubAlloc &freeBlock : g_megaFreeList) {
    freeBytes += freeBlock.size;
  }
  if (g_megaFreeList.size() > 64 ||
      (g_megaVBCapacity > 0 && freeBytes > (g_megaVBCapacity / 5))) {
    megaTrimFreeTail();
  }
}
static size_t megaGetOffset(uint64_t handle) {
  std::shared_lock<std::shared_mutex> lock(g_megaMutex);
  auto it = g_megaAllocs.find(handle);
  return (it != g_megaAllocs.end()) ? it->second.offset : 0;
}
static void *megaGetPointer(uint64_t handle) {
  std::shared_lock<std::shared_mutex> lock(g_megaMutex);
  auto it = g_megaAllocs.find(handle);
  if (it == g_megaAllocs.end() || !g_megaVB)
    return nullptr;
  return (char *)[g_megaVB contents] + it->second.offset;
}
static bool megaGetAlloc(uint64_t handle, MegaSubAlloc &out) {
  std::shared_lock<std::shared_mutex> lock(g_megaMutex);
  auto it = g_megaAllocs.find(handle);
  if (it == g_megaAllocs.end() || !g_megaVB)
    return false;
  out = it->second;
  return true;
}
static id<MTLRenderPipelineState> g_pipelineOpaque = nil;
static id<MTLRenderPipelineState> g_pipelineInhouse = nil;
static id<MTLRenderPipelineState> g_pipelineWater = nil;
static id<MTLRenderPipelineState> g_pipelineCutout = nil;
static id<MTLRenderPipelineState> g_pipelineTranslucent = nil;
static id<MTLRenderPipelineState> g_pipelineEntity = nil;
static id<MTLRenderPipelineState> g_pipelineEntityInstanced = nil;
static id<MTLRenderPipelineState> g_pipelineEntityTranslucent = nil;
static id<MTLRenderPipelineState> g_pipelineEntityEmissive = nil;
static id<MTLRenderPipelineState> g_pipelineEntityOutline = nil;
static id<MTLRenderPipelineState> g_pipelineEntityShadow = nil;
static id<MTLRenderPipelineState> g_pipelineParticle = nil;
static id<MTLRenderPipelineState> g_pipelineDebugLines = nil;
id<MTLDepthStencilState> g_depthState = nil;
static id<MTLDepthStencilState> g_depthStateNoWrite = nil;
static id<MTLDepthStencilState> g_depthStateLessEqual = nil;

static id<MTLDepthStencilState> g_depthStateEqualNoWrite = nil;

static id<MTLTexture> g_tbColor[3] = {};
static id<MTLTexture> g_tbDepth[3] = {};
#ifdef METALRENDER_HAS_METALFX

static id<MTLTexture> g_lrColor[3] = {};
static id<MTLTexture> g_lrDepth[3] = {};
// MetalFX requires a private output texture. The IOSurface-backed presentation
// textures are shared, so scale into this private target and copy into the
// presentation slot afterwards.
static id<MTLTexture> g_mfxOutput[3] = {};
static id<MTLFXSpatialScaler> g_mfxScaler = nil;
#endif
static IOSurfaceRef g_tbIOSurface[3] = {};
enum SurfaceSlotState : int {
  SurfaceSlotAvailable = 0,
  SurfaceSlotMetalInFlight = 1,
  SurfaceSlotReadyForPresentation = 2,
  SurfaceSlotBoundToGL = 3,
};
static std::atomic<bool> g_tbSlotReady[3] = {{true}, {true}, {true}};
static std::atomic<int> g_tbSlotState[3] = {
    {SurfaceSlotAvailable}, {SurfaceSlotAvailable}, {SurfaceSlotAvailable}};
static std::mutex g_surfaceSlotMutex;
static std::condition_variable g_surfaceSlotChanged;
static std::atomic<int> g_glBoundSlot{-1};
static std::atomic<int> g_tbLastCompleted{-1};
static int g_renderSlot = 0;
static bool g_wasReuseFrame = false;
static bool g_wasLowResolutionFrame = false;
static id<MTLCommandBuffer> g_tbCmdBuf[3] = {};
id<MTLRenderCommandEncoder> g_currentEncoder = nil;
static id<MTLCommandBuffer> g_currentCmdBuffer = nil;

static id<MTLTexture> g_color = nil;
static id<MTLTexture> g_depth = nil;
static id<MTLTexture> g_frameColorTarget = nil;
static id<MTLTexture> g_frameDepthTarget = nil;
static IOSurfaceRef g_ioSurface = NULL;
static id<MTLBuffer> g_tbDepthReadBuffer[3] = {};
static NSUInteger g_depthReadBytesPerRow = 0;
static int g_depthReadWidth = 0;
static int g_depthReadHeight = 0;
static id<MTLTexture> g_blockAtlas = nil;
static id<MTLTexture> g_lightmap = nil;
static id<MTLLibrary> g_shaderLibrary = nil;
static int g_rtWidth = 16;
static int g_rtHeight = 16;
static float g_scale = 1.0f;
static int g_allocatedRenderWidth = 0;
static int g_allocatedRenderHeight = 0;
static std::atomic<int> g_frameCount{0};
static std::atomic<float> g_lastGpuMs{0.0f};
// Metal 4 full-graph telemetry is deliberately independent from the base
// renderer timer above. MTL4 commit feedback is observational only: shared
// events remain the sole command-storage and resource-lifetime fence.
static std::atomic<uint64_t> g_irisMetal4GraphGpuSamples{0};
static std::atomic<uint64_t> g_irisMetal4GraphLastGpuNs{0};
static std::atomic<uint64_t> g_irisMetal4GraphTotalGpuNs{0};
static std::atomic<uint64_t> g_irisMetal4GraphMaxGpuNs{0};
static std::atomic<uint64_t> g_irisMetal4GraphLastQueueNs{0};
static std::atomic<uint64_t> g_irisMetal4GraphTotalQueueNs{0};
static std::atomic<uint64_t> g_irisMetal4GraphMaxQueueNs{0};
static std::atomic<uint64_t> g_irisMetal4GraphFeedbackErrors{0};
static std::atomic<uint64_t> g_irisMetal4GraphCpuSamples{0};
static std::atomic<uint64_t> g_irisMetal4GraphLastCpuNs{0};
static std::atomic<uint64_t> g_irisMetal4GraphTotalCpuNs{0};
static std::atomic<uint64_t> g_irisMetal4GraphMaxCpuNs{0};
// Raw commit-feedback samples are disabled in normal gameplay. Exact-JAR QA
// enables this bounded buffer explicitly so performance acceptance never has
// to infer a distribution from process-lifetime totals or the last frame.
static constexpr size_t kIrisMetal4GraphPerformanceSampleLimit = 36'000;
static std::atomic<bool> g_irisMetal4GraphPerformanceSampling{false};
static std::mutex g_irisMetal4GraphPerformanceMutex;
static std::vector<uint64_t> g_irisMetal4GraphPerformanceSamples;
static uint64_t g_irisMetal4GraphPerformanceDropped = 0;
static std::mutex g_gpuTelemetryMutex;
static uint64_t g_gpuTelemetryAccumulatedUs = 0;
static uint32_t g_gpuTelemetryCompletedFrames = 0;
uint32_t g_drawCallCount = 0;
static int g_drawSkipCount = 0;
static int g_totalDraws = 0;
static uint64_t g_prof_drawAll_acc = 0;
static uint64_t g_prof_endFrame_acc = 0;
static uint64_t g_prof_waitRender_acc = 0;
static uint64_t g_prof_cglBind_acc = 0;
static int g_prof_count = 0;
static const int kProfileEmitInterval = 240;
static id<MTLTexture> g_entityTexture = nil;
static float g_entityOverlayParams[4] = {0, 0, 0, 1};
static float g_entityTintColor[4] = {1, 1, 1, 1};
static id<MTLIndirectCommandBuffer> g_icb[3] = {};

static NSUInteger g_icbMaxCommands[3] = {};
static const NSUInteger ICB_INITIAL_SIZE = 8192;
static id<MTLRenderPipelineState> g_pipelineInhouseICB = nil;
static id<MTLRenderPipelineState> g_pipelineInhouseICBOpaque = nil;
static id<MTLRenderPipelineState> g_pipelineInhouseOpaque = nil;

id<MTLRenderPipelineState> g_pipelineMeshOpaque = nil;
id<MTLRenderPipelineState> g_pipelineMeshCutout = nil;
id<MTLRenderPipelineState> g_pipelineMeshEmissive = nil;
static id<MTLBuffer> g_fragArgBuf[3] = {};
static id<MTLBuffer> g_fragArgBufOpaque[3] = {};
static id<MTLArgumentEncoder> g_fragArgEncoder = nil;
static id<MTLArgumentEncoder> g_fragArgEncoderOpaque = nil;
static id<MTLComputePipelineState> g_hizDownsamplePipeline = nil;
static id<MTLComputePipelineState> g_hizMultiPipeline = nil;
static id<MTLComputePipelineState> g_cullEncodePipeline = nil;
static id<MTLComputePipelineState> g_resetCullPipeline = nil;
static id<MTLTexture> g_hizPyramid = nil;
static id<MTLTexture> g_hizFallbackTexture = nil;
static MTLSize g_hizThreads = {16, 16, 1};
static MTLSize g_hizGroups = {0, 0, 0};
static int g_hizCachedW = 0;
static int g_hizCachedH = 0;
static NSUInteger g_cullMaxTG = 0;
static uint32_t g_hizMipCount = 0;
// The existing multi-mip builder did not bind its parameter buffer and only
// populated the first level. Keep occlusion disabled until the complete path is
// validated; shaders still use reversed-Z semantics for the eventual re-enable.
static constexpr bool kHiZPathValidated = false;
// The helper currently disagrees with the mesh payload/threadgroup layout used
// by mesh_terrain.metal. Do not advertise or dispatch it until that ABI has a
// GPU-validation test.
static constexpr bool kMeshShaderPathValidated = false;

static inline void hizUpdateThreadgroupSize(id<MTLTexture> srcDepth) {
  int w = (int)srcDepth.width;
  int h = (int)srcDepth.height;
  if (w != g_hizCachedW || h != g_hizCachedH) {
    g_hizCachedW = w;
    g_hizCachedH = h;
    g_hizGroups = MTLSizeMake((w + 15) / 16, (h + 15) / 16, 1);
  }
}
static int g_hizWidth = 0;
static int g_hizHeight = 0;
static id<MTLBuffer> g_hizReadbackBuf = nil;
static int g_hizReadbackW = 0;
static int g_hizReadbackH = 0;
static const uint32_t HIZ_READBACK_MIP = 3;
static float g_vpMatrix[16] = {0};
static id<MTLTexture> g_hizSrcViews[16] = {};
static id<MTLTexture> g_hizDstViews[16] = {};
static int g_hizViewsValid = 0;
id<MTLBuffer> g_cullDrawArgsBuffer = nil;
id<MTLBuffer> g_cullDrawCountBuffer = nil;
static id<MTLBuffer> g_cullStatsBuffer = nil;
id<MTLBuffer> g_subChunkBuffer = nil;
static id<MTLBuffer> g_chunkUniformsBuffer = nil;
static id<MTLBuffer> g_cameraUniformsBuffer = nil;
static id<MTLBuffer> g_visibleIndicesBuffer = nil;
static uint32_t g_maxGPUDrawCalls = 65536;
uint32_t g_gpuSubChunkCount = 0;
static bool g_gpuDrivenEnabled = false;
static bool g_icbCapable = false;
static id<MTLSharedEvent> g_frameEvent = nil;
static MTLSharedEventListener *g_eventListener = nil;
static uint64_t g_eventCounter = 0;
id<MTLBuffer> g_tripleBuffers[kTripleBufferCount] = {};

static id<MTLBuffer> g_meshletBuffers[kTripleBufferCount] = {};
int g_currentBufferIndex = 0;
static dispatch_semaphore_t g_frameSemaphore = nil;
static std::atomic<bool> g_shuttingDown{false};
static std::atomic<bool> g_currentFrameReady{true};
static id<MTLBuffer> g_argumentBuffer = nil;
static bool g_argumentBufferDirty = true;
static id<MTLRenderPipelineState> g_meshTerrainOpaquePSO = nil;
static id<MTLRenderPipelineState> g_meshTerrainCutoutPSO = nil;
static id<MTLRenderPipelineState> g_meshTerrainEmissivePSO = nil;
bool g_meshShadersActive = false;
uint32_t g_meshPipelineCount = 0;
static int g_thermalState = 0;
static double g_lastThermalCheckTime = 0;
static float g_skyBrightness = 1.0f;

struct StaleDrawCmd {
  uint64_t bufferHandle;
  int idxCount;
  int opaqueIdxCount;
  int opaqueFaceCounts[7];
  float ox, oy, oz;
  bool isMega;
};
static StaleDrawCmd *g_staleDrawCmds = nullptr;
static int g_staleDrawCount = 0;
static int g_staleMegaCount = 0;
static int g_staleCapacity = 0;
static float *g_staleOffsetData = nullptr;
static int g_staleOffsetCapacity = 0;
static bool g_hasStaleDrawList = false;
static float g_staleCamX = 0, g_staleCamY = 0, g_staleCamZ = 0;

static float g_dynamicLODScale = 1.0f;
static float g_targetFrameTimeMs = 16.67f;
static float g_avgFrameTimeMs = 0.0f;
static int g_configuredRenderDistBlocks = 512;
static bool g_useMemorylessTargets = false;
static float g_targetScale = 1.0f;
static bool g_useProgrammableBlending = false;
static bool g_useArgumentBuffers = false;
static id<MTLTexture> g_oitAccumTex = nil;
static id<MTLTexture> g_oitRevealTex = nil;
static id<MTLRenderPipelineState> g_pipelineOITAccum = nil;
static id<MTLRenderPipelineState> g_pipelineOITComposite = nil;

struct OITCachedCmd {
  __unsafe_unretained id<MTLBuffer> resolvedBuf;
  size_t megaOffset;
  bool isMega;
  int translucentIdxCount;
  int instanceIdx;
  int opaqueVertCount;
};
static OITCachedCmd *g_oitCmds = nullptr;
static int g_oitCmdsCapacity = 0;
static int g_oitCmdsCount = 0;
static __unsafe_unretained id<MTLBuffer> g_oitIB = nil;
static NSUInteger g_oitIBOffset = 0;
static __unsafe_unretained id<MTLBuffer> g_oitOffsetBuf = nil;

struct DeferredWaterCmd {
  __unsafe_unretained id<MTLBuffer> resolvedBuf;
  size_t megaOffset;
  int idxCount;
  int opaqueIdxCount;
  float distSq;
  int instanceIdx;
  bool isMega;
};
static DeferredWaterCmd *g_deferredWaterCmds = nullptr;
static int g_deferredWaterCapacity = 0;
static int g_deferredWaterCmdCount = 0;
static __unsafe_unretained id<MTLBuffer> g_deferredWaterIB = nil;
static NSUInteger g_deferredWaterIBOffset = 0;
static __unsafe_unretained id<MTLBuffer> g_deferredWaterOffsetBuf = nil;

static int g_thermalQualityLevel = 0;

static bool g_supportsASTC = false;
static id<MTLDepthStencilState> g_depthStateReversedZ = nil;
static id<MTLDepthStencilState> g_depthStateReversedZNoWrite = nil;
static id<MTLDepthStencilState> g_depthStateReversedZReadOnly = nil;
struct NativeMesh {
  int32_t chunkX, chunkY, chunkZ;
  uint64_t bufferHandle;
  int32_t quadCount;
  int32_t opaqueQuadCount;
  uint64_t visibilityMask;
  int32_t facingQuadCounts[14];
  bool active;
};

static inline uint32_t visibleFacingMaskForAabb(float ox, float oy, float oz) {
  uint32_t mask = 1u << 6;
  if (0.0f < oy)
    mask |= 1u << 0;
  else if (0.0f > oy + 16.0f)
    mask |= 1u << 1;
  else
    mask |= (1u << 0) | (1u << 1);
  if (0.0f < oz)
    mask |= 1u << 2;
  else if (0.0f > oz + 16.0f)
    mask |= 1u << 3;
  else
    mask |= (1u << 2) | (1u << 3);
  if (0.0f < ox)
    mask |= 1u << 4;
  else if (0.0f > ox + 16.0f)
    mask |= 1u << 5;
  else
    mask |= (1u << 4) | (1u << 5);
  return mask;
}

static inline int opaqueBucketStartQuad(const int counts[7], int bucket) {
  int start = 0;
  for (int i = 0; i < bucket; i++)
    start += counts[i];
  return start;
}

static size_t clamp_memory_budget(size_t requestedBytes) {
  size_t recommended = 0;
  if (g_device &&
      [g_device respondsToSelector:@selector(recommendedMaxWorkingSetSize)]) {
    recommended = (size_t)g_device.recommendedMaxWorkingSetSize;
  }
  size_t safeDeviceBudget =
      recommended > 0 ? std::max(kMegaVBMinimum, recommended / 8)
                      : kMegaVBDefaultMaximum;
  safeDeviceBudget = std::min(safeDeviceBudget, kMegaVBHardMaximum);
  if (requestedBytes == 0)
    return safeDeviceBudget;
  return std::max(kMegaVBMinimum,
                  std::min(requestedBytes, safeDeviceBudget));
}

static size_t requested_memory_budget_from_environment() {
  const char *value = std::getenv("METALRENDER_MAX_MEMORY_MB");
  if (!value || value[0] == '\0')
    return 0;
  char *end = nullptr;
  unsigned long long megabytes = std::strtoull(value, &end, 10);
  if (end == value || megabytes == 0)
    return 0;
  return (size_t)std::min<unsigned long long>(
             megabytes, kMegaVBHardMaximum / kMiB) *
         kMiB;
}

static void recreate_mega_vertex_buffer_if_empty() {
  if (!g_device)
    return;
  if (g_requestedMemoryBudgetBytes == 0)
    g_requestedMemoryBudgetBytes = requested_memory_budget_from_environment();
  g_megaVBBudget = clamp_memory_budget(g_requestedMemoryBudgetBytes);
  size_t desiredCapacity = std::min(
      std::max(kMegaVBMinimum, g_megaVBBudget / 2),
      kMegaVBDefaultMaximum);
  desiredCapacity = std::max(desiredCapacity, kMegaVBMinimum);

  std::unique_lock<std::shared_mutex> lock(g_megaMutex);
  if (g_megaVB && (g_megaVBHead != 0 || !g_megaAllocs.empty()))
    return;
  if (g_megaVB && g_megaVBCapacity == desiredCapacity)
    return;
  if (g_megaVB) {
    [g_megaVB release];
    g_megaVB = nil;
  }
  g_megaVB = [g_device newBufferWithLength:desiredCapacity
                                    options:MTLStorageModeShared];
  g_megaVBCapacity = g_megaVB ? desiredCapacity : 0;
  g_megaVBHead = 0;
  g_megaFreeList.clear();
  if (g_megaVB) {
    g_megaVB.label = @"MetalRender bounded vertex arena";
    dbg("Vertex arena created: capacity=%zuMB budget=%zuMB\n",
        g_megaVBCapacity / kMiB, g_megaVBBudget / kMiB);
  } else {
    dbg("WARN: Failed to create bounded vertex arena; using individual "
        "buffers\n");
  }
}

struct Metal4ProbeState {
  id<MTLSharedEvent> completionEvent;
  uint64_t completionValue = 1;
  std::atomic<bool> completed{false};
  std::atomic<bool> succeeded{false};
  id allocator;

  explicit Metal4ProbeState(id probeAllocator)
      : completionEvent([g_device newSharedEvent]),
        allocator([probeAllocator retain]) {}

  ~Metal4ProbeState() {
    if (completionEvent)
      [completionEvent release];
    if (allocator)
      [allocator release];
  }
};

// Follow Apple's Metal 4 frame lifecycle directly: enqueue the command buffer,
// enqueue a shared-event signal after it, wait for that GPU-timeline value,
// then reset the allocator only once Metal says its storage is reusable.  A
// feedback handler runs inside IOGPUMetalCommandBuffer::didComplete; using it
// as a CPU lifetime fence allowed command-storage reset and block disposal to
// overlap the submitter on macOS 26.  Shared events are the documented reuse
// boundary and require no completion callback or captured C++ object.
static bool metal4_commit_and_wait(
    id<MTL4CommandQueue> queue, id<MTL4CommandBuffer> commandBuffer,
    const std::shared_ptr<Metal4ProbeState> &state, uint64_t timeoutMs)
    API_AVAILABLE(macos(26.0)) {
  if (!queue || !commandBuffer || !state || !state->completionEvent
      || !state->allocator || timeoutMs == 0) {
    return false;
  }
  id<MTL4CommandBuffer> buffers[] = {commandBuffer};
  [queue commit:buffers count:1];
  [queue signalEvent:state->completionEvent value:state->completionValue];
  bool completed = [state->completionEvent
      waitUntilSignaledValue:state->completionValue timeoutMS:timeoutMs];
  if (completed) {
    [(id<MTL4CommandAllocator>)state->allocator reset];
  }
  // Publish completion only after the allocator has crossed its documented
  // reuse boundary.  Retirement code treats this flag as permission to drop
  // every unretained Metal 4 resource referenced by the command buffer.
  state->succeeded.store(completed, std::memory_order_release);
  state->completed.store(completed, std::memory_order_release);
  return completed;
}

static bool metal4_commit_without_wait(
    id<MTL4CommandQueue> queue, id<MTL4CommandBuffer> commandBuffer,
    const std::shared_ptr<Metal4ProbeState> &state,
    MTL4CommitOptions *options)
    API_AVAILABLE(macos(26.0)) {
  if (!queue || !commandBuffer || !state || !state->completionEvent ||
      !state->allocator) {
    return false;
  }
  id<MTL4CommandBuffer> buffers[] = {commandBuffer};
  if (options) {
    [queue commit:buffers count:1 options:options];
  } else {
    [queue commit:buffers count:1];
  }
  [queue signalEvent:state->completionEvent value:state->completionValue];
  return true;
}

static bool iris_metal4_submission_completed(
    const std::shared_ptr<Metal4ProbeState> &state)
    API_AVAILABLE(macos(26.0)) {
  if (!state)
    return true;
  if (state->completed.load(std::memory_order_acquire))
    return true;
  // A bounded synchronous wait can time out immediately before the GPU reaches
  // the queued shared-event signal.  Recover that submission on a later entry
  // instead of quarantining its unretained resources for the process lifetime.
  if (!state->completionEvent ||
      state->completionEvent.signaledValue < state->completionValue) {
    return false;
  }
  if (state->allocator)
    [(id<MTL4CommandAllocator>)state->allocator reset];
  state->succeeded.store(true, std::memory_order_release);
  state->completed.store(true, std::memory_order_release);
  return true;
}

// Emergency lifetime fence for a command buffer that was committed before a
// retirement container could be installed. The steady-state async path never
// waits here: it preflights a bounded retirement slot and reserves all vector
// storage before committing. If an allocation still fails, correctness wins
// over frame latency and every referenced object remains alive until Metal's
// shared-event boundary is observed.
static bool iris_metal4_wait_for_committed_submission(
    const std::shared_ptr<Metal4ProbeState> &state, uint64_t timeoutMs)
    API_AVAILABLE(macos(26.0)) {
  if (iris_metal4_submission_completed(state))
    return true;
  if (!state || !state->completionEvent || !state->allocator ||
      timeoutMs == 0) {
    return false;
  }
  bool completed = [state->completionEvent
      waitUntilSignaledValue:state->completionValue timeoutMS:timeoutMs];
  if (!completed)
    return false;
  [(id<MTL4CommandAllocator>)state->allocator reset];
  state->succeeded.store(true, std::memory_order_release);
  state->completed.store(true, std::memory_order_release);
  return true;
}

static bool verify_metal4_runtime(id queueObject) {
  if (!queueObject || !g_device)
    return false;
  if (@available(macOS 26.0, *)) {
    id<MTL4CommandAllocator> probeAllocator = nil;
    id<MTL4CommandBuffer> commandBuffer = nil;
    MTL4CommitOptions *options = nil;
    @try {
      id<MTL4CommandQueue> queue =
          (id<MTL4CommandQueue>)queueObject;
      probeAllocator = [g_device newCommandAllocator];
      commandBuffer = [g_device newCommandBuffer];
      if (!probeAllocator || !commandBuffer) {
        if (probeAllocator)
          [probeAllocator release];
        if (commandBuffer)
          [commandBuffer release];
        return false;
      }

      [commandBuffer beginCommandBufferWithAllocator:probeAllocator];
      id<MTL4ComputeCommandEncoder> encoder =
          [commandBuffer computeCommandEncoder];
      if (!encoder) {
        [commandBuffer endCommandBuffer];
        [commandBuffer release];
        [probeAllocator release];
        return false;
      }
      [encoder endEncoding];
      [commandBuffer endCommandBuffer];

      auto state = std::make_shared<Metal4ProbeState>(probeAllocator);
      bool verified = metal4_commit_and_wait(
          queue, commandBuffer, state, 2'000) &&
          state->completed.load(std::memory_order_acquire) &&
          state->succeeded.load(std::memory_order_acquire);

      [options release];
      [commandBuffer release];
      [probeAllocator release];
      return verified;
    } @catch (NSException *exception) {
      dbg("WARN: Metal 4 probe raised %s: %s\n",
          exception.name.UTF8String ?: "NSException",
          exception.reason.UTF8String ?: "unknown reason");
      if (options)
        [options release];
      if (commandBuffer)
        [commandBuffer release];
      if (probeAllocator)
        [probeAllocator release];
      return false;
    }
  }
  return false;
}

static void configure_metal4_scaffold() {
  if (!g_device)
    return;
  std::lock_guard<std::mutex> lock(g_metal4Mutex);
  bool supported = false;
  if (@available(macOS 26.0, *)) {
    supported = NSClassFromString(@"MTL4CommandQueueDescriptor") != Nil &&
                [g_device respondsToSelector:@selector(newMTL4CommandQueue)] &&
                [g_device respondsToSelector:@selector(newCommandAllocator)] &&
                [g_device respondsToSelector:@selector(newCommandBuffer)];
    g_metal4Supported.store(supported, std::memory_order_release);
    if (supported && g_metal4Requested.load(std::memory_order_acquire) &&
        !g_metal4ProbeAttempted.load(std::memory_order_acquire)) {
      // Publish the transient state before marking the attempt as started.
      // nGetBackendMode is queried concurrently by the Iris translation
      // worker; without this state it can observe requested+supported while
      // the bounded probe is still running and misreport a terminal failure.
      // The Java pipeline compiler intentionally memoizes terminal fallback,
      // so that short window otherwise poisons the entire cold-cache run.
      g_metal4ProbeInProgress.store(true, std::memory_order_release);
      g_metal4ProbeAttempted.store(true, std::memory_order_release);
      // A first no-op commit can miss its bounded completion window while the
      // driver is cold or another API is compiling shaders. One timeout must
      // not permanently downgrade an otherwise healthy Metal 4 runtime, so
      // retry once with fresh queue/allocator objects. The total wait remains
      // bounded and a second failure still selects the normal Metal 3 path.
      id<MTL4CommandQueue> queue = nil;
      id<MTL4CommandAllocator> allocator = nil;
      bool verified = false;
      for (int attempt = 0; attempt < 2 && !verified; attempt++) {
        queue = [g_device newMTL4CommandQueue];
        allocator = [g_device newCommandAllocator];
        verified = queue && allocator && verify_metal4_runtime(queue);
        if (!verified) {
          if (queue)
            [queue release];
          if (allocator)
            [allocator release];
          queue = nil;
          allocator = nil;
          if (attempt == 0)
            dbg("WARN: Metal 4 command-buffer probe retrying after bounded "
                "first failure\n");
        }
      }
      if (verified) {
        g_metal4CommandQueue = queue;
        g_metal4CommandAllocator = allocator;
        g_metal4ScaffoldActive.store(true, std::memory_order_release);
        g_metal4RuntimeVerified.store(true, std::memory_order_release);
        g_metal4ProbeInProgress.store(false, std::memory_order_release);
        dbg("Metal 4 runtime verified by command-buffer completion; rendering "
            "remains on the compatibility command stream\n");
      } else {
        if (queue)
          [queue release];
        if (allocator)
          [allocator release];
        g_metal4ScaffoldActive.store(false, std::memory_order_release);
        g_metal4RuntimeVerified.store(false, std::memory_order_release);
        g_metal4ProbeInProgress.store(false, std::memory_order_release);
        dbg("WARN: Metal 4 command-buffer probe failed; using Metal 3 "
            "fallback\n");
      }
    }
  } else {
    g_metal4Supported.store(false, std::memory_order_release);
    g_metal4ScaffoldActive.store(false, std::memory_order_release);
    g_metal4RuntimeVerified.store(false, std::memory_order_release);
    g_metal4ProbeInProgress.store(false, std::memory_order_release);
  }
}

static inline int visibleOpaqueBucketCount(const int counts[7], uint32_t mask) {
  int total = 0;
  for (int i = 0; i < 7; i++) {
    if ((mask & (1u << i)) != 0 && counts[i] > 0)
      total++;
  }
  return total;
}

struct CameraUniformsCPU {
  float viewProjection[16];
  float projection[16];
  float modelView[16];
  float cameraPosition[4];
  float frustumPlanes[24];
  float screenSize[2];
  float nearPlane;
  float farPlane;
  uint32_t frameIndex;
  uint32_t hizMipCount;
  uint32_t totalChunks;
  float waterFog;
};

struct ChunkMeshletNative {
  uint32_t baseVertexOffset;
  uint32_t vertexCount;
  float worldX;
  float worldY;
  float worldZ;
  uint32_t _pad0;
  uint32_t _pad1;
  uint32_t _pad2;
};
static_assert(sizeof(ChunkMeshletNative) == 32,
              "ChunkMeshletNative must be 32 bytes");
static std::vector<NativeMesh> g_nativeMeshes;
static std::unordered_map<int64_t, size_t> g_meshKeyToIdx;
static std::vector<size_t> g_meshFreeSlots;

static std::vector<int> g_activeMeshIndices;
static std::shared_mutex g_meshRegMutex;
static int g_activeMeshCount = 0;
static int64_t packMeshKey(int cx, int cy, int cz) {
  return ((int64_t)(cx & 0x3FFFFF) << 42) | ((int64_t)(cy & 0xFFFFF) << 22) |
         (int64_t)(cz & 0x3FFFFF);
}
static inline bool frustumTestAABB(const float p[24], float x0, float y0,
                                   float z0, float x1, float y1, float z1) {
#ifdef __aarch64__

  float32x4_t vx0 = vdupq_n_f32(x0), vx1 = vdupq_n_f32(x1);
  float32x4_t vy0 = vdupq_n_f32(y0), vy1 = vdupq_n_f32(y1);
  float32x4_t vz0 = vdupq_n_f32(z0), vz1 = vdupq_n_f32(z1);
  float32x4_t zero = vdupq_n_f32(0.0f);
  for (int i = 0; i < 6; i += 2) {
    if (i != 4) {
      float32x4_t plane0 = vld1q_f32(&p[i * 4]);

      float32x4_t a0 = vdupq_laneq_f32(plane0, 0);
      float32x4_t b0 = vdupq_laneq_f32(plane0, 1);
      float32x4_t c0 = vdupq_laneq_f32(plane0, 2);
      float32x4_t d0 = vdupq_laneq_f32(plane0, 3);

      uint32x4_t maskA0 = vcgeq_f32(a0, zero);
      uint32x4_t maskB0 = vcgeq_f32(b0, zero);
      uint32x4_t maskC0 = vcgeq_f32(c0, zero);
      float32x4_t px0 = vbslq_f32(maskA0, vx1, vx0);
      float32x4_t py0 = vbslq_f32(maskB0, vy1, vy0);
      float32x4_t pz0 = vbslq_f32(maskC0, vz1, vz0);

      float32x4_t dot0 = vfmaq_f32(d0, a0, px0);
      dot0 = vfmaq_f32(dot0, b0, py0);
      dot0 = vfmaq_f32(dot0, c0, pz0);
      if (vgetq_lane_f32(dot0, 0) < 0)
        return false;
    }
    if (i + 1 < 6 && i + 1 != 4) {
      float32x4_t plane1 = vld1q_f32(&p[(i + 1) * 4]);
      float32x4_t a1 = vdupq_laneq_f32(plane1, 0);
      float32x4_t b1 = vdupq_laneq_f32(plane1, 1);
      float32x4_t c1 = vdupq_laneq_f32(plane1, 2);
      float32x4_t d1 = vdupq_laneq_f32(plane1, 3);
      uint32x4_t maskA1 = vcgeq_f32(a1, zero);
      uint32x4_t maskB1 = vcgeq_f32(b1, zero);
      uint32x4_t maskC1 = vcgeq_f32(c1, zero);
      float32x4_t px1 = vbslq_f32(maskA1, vx1, vx0);
      float32x4_t py1 = vbslq_f32(maskB1, vy1, vy0);
      float32x4_t pz1 = vbslq_f32(maskC1, vz1, vz0);
      float32x4_t dot1 = vfmaq_f32(d1, a1, px1);
      dot1 = vfmaq_f32(dot1, b1, py1);
      dot1 = vfmaq_f32(dot1, c1, pz1);
      if (vgetq_lane_f32(dot1, 0) < 0)
        return false;
    }
  }
  return true;
#else
  for (int i = 0; i < 6; i++) {
    if (i == 4)
      continue;
    float a = p[i * 4], b = p[i * 4 + 1], c = p[i * 4 + 2], d = p[i * 4 + 3];
    float px = (a >= 0) ? x1 : x0;
    float py = (b >= 0) ? y1 : y0;
    float pz = (c >= 0) ? z1 : z0;
    if (a * px + b * py + c * pz + d < 0)
      return false;
  }
  return true;
#endif
}

#ifdef __aarch64__

static inline uint32_t frustumTestAABB_x4(const float p[24], const float ox[4],
                                          const float oy[4],
                                          const float oz[4]) {

  float32x4_t x0 = vld1q_f32(ox);
  float32x4_t y0 = vld1q_f32(oy);
  float32x4_t z0 = vld1q_f32(oz);

  float32x4_t sixteen = vdupq_n_f32(16.0f);
  float32x4_t x1 = vaddq_f32(x0, sixteen);
  float32x4_t y1 = vaddq_f32(y0, sixteen);
  float32x4_t z1 = vaddq_f32(z0, sixteen);
  float32x4_t zero = vdupq_n_f32(0.0f);

  uint32x4_t visible = vdupq_n_u32(0xFFFFFFFF);
  for (int i = 0; i < 6; i++) {
    if (i == 4)
      continue;
    float a = p[i * 4], b = p[i * 4 + 1], c = p[i * 4 + 2], d = p[i * 4 + 3];

    float32x4_t va = vdupq_n_f32(a);
    float32x4_t vb = vdupq_n_f32(b);
    float32x4_t vc = vdupq_n_f32(c);
    float32x4_t vd = vdupq_n_f32(d);

    uint32x4_t maskA = vcgeq_f32(va, zero);
    uint32x4_t maskB = vcgeq_f32(vb, zero);
    uint32x4_t maskC = vcgeq_f32(vc, zero);
    float32x4_t px = vbslq_f32(maskA, x1, x0);
    float32x4_t py = vbslq_f32(maskB, y1, y0);
    float32x4_t pz = vbslq_f32(maskC, z1, z0);

    float32x4_t dot = vfmaq_f32(vd, va, px);
    dot = vfmaq_f32(dot, vb, py);
    dot = vfmaq_f32(dot, vc, pz);

    uint32x4_t notBehind = vcgeq_f32(dot, zero);
    visible = vandq_u32(visible, notBehind);

    if (vmaxvq_u32(visible) == 0)
      return 0;
  }

  uint32_t mask = 0;
  uint32_t v[4];
  vst1q_u32(v, visible);
  if (v[0])
    mask |= 1;
  if (v[1])
    mask |= 2;
  if (v[2])
    mask |= 4;
  if (v[3])
    mask |= 8;
  return mask;
}
#endif

static void extractFrustumPlanes(const float m[16], float out[24]) {

  out[0] = m[3] + m[0];
  out[1] = m[7] + m[4];
  out[2] = m[11] + m[8];
  out[3] = m[15] + m[12];

  out[4] = m[3] - m[0];
  out[5] = m[7] - m[4];
  out[6] = m[11] - m[8];
  out[7] = m[15] - m[12];

  out[8] = m[3] + m[1];
  out[9] = m[7] + m[5];
  out[10] = m[11] + m[9];
  out[11] = m[15] + m[13];

  out[12] = m[3] - m[1];
  out[13] = m[7] - m[5];
  out[14] = m[11] - m[9];
  out[15] = m[15] - m[13];

  out[16] = m[2];
  out[17] = m[6];
  out[18] = m[10];
  out[19] = m[14];

  out[20] = m[3] - m[2];
  out[21] = m[7] - m[6];
  out[22] = m[11] - m[10];
  out[23] = m[15] - m[14];
  for (int i = 0; i < 6; i++) {
    float a = out[i * 4], b = out[i * 4 + 1], c = out[i * 4 + 2];
    float len = sqrtf(a * a + b * b + c * c);
    if (len > 0) {
      float inv = 1.0f / len;
      out[i * 4] *= inv;
      out[i * 4 + 1] *= inv;
      out[i * 4 + 2] *= inv;
      out[i * 4 + 3] *= inv;
    }
  }
}
static void ensure_device() {
  if (!g_device) {
    g_device = MTLCreateSystemDefaultDevice();
    if (g_device) {
      g_queue = [g_device newCommandQueue];
      g_queue.label = @"MetalRender Metal 3 compatibility queue";

      g_supportsASTC = [g_device supportsFamily:MTLGPUFamilyApple1];
      if (g_supportsASTC) {
        dbg("ASTC texture compression supported (Apple GPU)\n");
      }
      recreate_mega_vertex_buffer_if_empty();
      configure_metal4_scaffold();
    }
  }
}

static void ensure_system_power_notifications() {
  static dispatch_once_t onceToken;
  dispatch_once(&onceToken, ^{
    NSNotificationCenter *center =
        [[NSWorkspace sharedWorkspace] notificationCenter];
    g_systemWillSleepObserver = [center
        addObserverForName:NSWorkspaceWillSleepNotification
                    object:nil
                     queue:nil
                usingBlock:^(NSNotification *) {
                  g_systemSleepCount.fetch_add(1,
                                               std::memory_order_release);
                }];
    g_systemDidWakeObserver = [center
        addObserverForName:NSWorkspaceDidWakeNotification
                    object:nil
                     queue:nil
                usingBlock:^(NSNotification *) {
                  g_systemWakeCount.fetch_add(1,
                                              std::memory_order_release);
                }];
  });
}

struct MetalFeatureCaps {
  bool argumentBuffers;
  bool indirectCommandBuffers;
  bool memorylessTargets;
  bool meshShaders;
};

static bool metal_supports_family(id<MTLDevice> device, MTLGPUFamily family) {
  if (!device)
    return false;
  if (@available(macOS 11.0, *)) {
    return [device supportsFamily:family];
  }
  return false;
}

static MetalFeatureCaps current_feature_caps() {
  ensure_device();
  MetalFeatureCaps caps = {};
  if (!g_device)
    return caps;

  const bool apple2 = metal_supports_family(g_device, MTLGPUFamilyApple2);
  const bool apple3 = metal_supports_family(g_device, MTLGPUFamilyApple3);
  const bool apple7 = metal_supports_family(g_device, MTLGPUFamilyApple7);
  const bool mac2 = metal_supports_family(g_device, MTLGPUFamilyMac2);

  caps.argumentBuffers = apple2 || mac2;
  caps.indirectCommandBuffers = apple3 || mac2;
  caps.memorylessTargets = apple2;
  caps.meshShaders = apple7 || mac2;

  if (@available(macOS 13.0, *)) {
    // Availability already satisfied.
  } else {
    caps.meshShaders = false;
  }
  caps.meshShaders = caps.meshShaders && kMeshShaderPathValidated;

  return caps;
}

static id<MTLBinaryArchive> g_pipelineArchive = nil;
static NSString *g_archivePath = nil;
static std::mutex g_pipelineArchiveMutex;
static bool g_pipelineArchiveDirty = false;
static std::atomic<uint32_t> g_pipelineArchiveWarningCount{0};

static void pipeline_archive_warning(const char *operation,
                                     const char *detail) {
  uint32_t warningNumber =
      g_pipelineArchiveWarningCount.fetch_add(1, std::memory_order_relaxed) + 1;
  if (warningNumber <= 8 || (warningNumber % 128) == 0) {
    fprintf(stderr, "[MetalRender] WARN: Pipeline cache %s: %s "
                    "(warning #%u)\n",
            operation, detail ? detail : "unknown error", warningNumber);
    fflush(stderr);
    dbg("WARN: Pipeline cache %s: %s (warning #%u)\n", operation,
        detail ? detail : "unknown error", warningNumber);
  }
}

static void pipeline_archive_warning(const char *operation, NSError *error) {
  pipeline_archive_warning(
      operation,
      error ? [[error localizedDescription] UTF8String] : "unknown error");
}

static id<MTLBinaryArchive> new_empty_pipeline_archive(NSError **outErr) {
  MTLBinaryArchiveDescriptor *descriptor =
      [[MTLBinaryArchiveDescriptor alloc] init];
  id<MTLBinaryArchive> archive =
      [g_device newBinaryArchiveWithDescriptor:descriptor error:outErr];
  [descriptor release];
  return archive;
}

// Must be called while g_pipelineArchiveMutex is held. A binary archive can
// load successfully yet reject population after an OS, GPU, driver, or shader
// change. Replace only the in-memory object first; the known-old file remains
// untouched until a replacement has accepted at least one pipeline and is
// serialized atomically.
static bool replace_pipeline_archive_with_empty(const char *operation) {
  NSError *replacementError = nil;
  id<MTLBinaryArchive> replacement =
      new_empty_pipeline_archive(&replacementError);
  if (!replacement) {
    pipeline_archive_warning(operation, replacementError);
    return false;
  }
  if (g_pipelineArchive)
    [g_pipelineArchive release];
  g_pipelineArchive = replacement;
  g_pipelineArchiveDirty = false;
  return true;
}

static id<MTLRenderPipelineState>
makePipeline(MTLRenderPipelineDescriptor *desc, NSError **outErr) {
  if (@available(macOS 11.0, *)) {
    if (g_pipelineArchive) {
      std::lock_guard<std::mutex> lock(g_pipelineArchiveMutex);
      NSArray<id<MTLBinaryArchive>> *originalArchives =
          [desc.binaryArchives retain];
      NSError *populationError = nil;
      BOOL populated = [g_pipelineArchive
          addRenderPipelineFunctionsWithDescriptor:desc
                                             error:&populationError];
      if (!populated) {
        pipeline_archive_warning("render population failed", populationError);
        // Never serialize an archive after Metal has rejected population.
        g_pipelineArchiveDirty = false;
        if (replace_pipeline_archive_with_empty(
                "render population recovery archive creation failed")) {
          populationError = nil;
          populated = [g_pipelineArchive
              addRenderPipelineFunctionsWithDescriptor:desc
                                                 error:&populationError];
          if (!populated) {
            pipeline_archive_warning(
                "render population retry with empty archive failed",
                populationError);
          }
        }
      }
      if (populated) {
        desc.binaryArchives = @[ g_pipelineArchive ];
      }

      NSError *creationError = nil;
      id<MTLRenderPipelineState> pipeline =
          [g_device newRenderPipelineStateWithDescriptor:desc
                                                   error:&creationError];
      if (pipeline && populated)
        g_pipelineArchiveDirty = true;
      if (!pipeline && populated) {
        // An archive can become unusable after an OS, GPU, driver, or shader
        // change. A cache hit must never prevent the pipeline from compiling.
        pipeline_archive_warning("render-assisted creation failed; retrying "
                                 "without the archive",
                                 creationError);
        desc.binaryArchives = originalArchives;
        creationError = nil;
        pipeline = [g_device newRenderPipelineStateWithDescriptor:desc
                                                            error:&creationError];
        // The archive accepted population but then failed assisted creation.
        // Quarantine it even when the uncached fallback succeeds; otherwise
        // the known-bad object would be serialized for the next launch.
        g_pipelineArchiveDirty = false;
        if (pipeline &&
            replace_pipeline_archive_with_empty(
                "render-assisted recovery archive creation failed")) {
          NSError *repopulationError = nil;
          BOOL repopulated = [g_pipelineArchive
              addRenderPipelineFunctionsWithDescriptor:desc
                                                 error:&repopulationError];
          if (repopulated) {
            g_pipelineArchiveDirty = true;
          } else {
            pipeline_archive_warning(
                "render-assisted recovery population failed",
                repopulationError);
          }
        }
      }
      desc.binaryArchives = originalArchives;
      [originalArchives release];
      if (outErr)
        *outErr = creationError;
      return pipeline;
    }
  }
  return [g_device newRenderPipelineStateWithDescriptor:desc error:outErr];
}

static id<MTLComputePipelineState> makeComputePipeline(id<MTLFunction> func,
                                                       NSError **outErr) {
  if (@available(macOS 11.0, *)) {
    MTLComputePipelineDescriptor *descriptor =
        [[MTLComputePipelineDescriptor alloc] init];
    descriptor.computeFunction = func;
    if (g_pipelineArchive) {
      std::lock_guard<std::mutex> lock(g_pipelineArchiveMutex);
      NSError *populationError = nil;
      BOOL populated = [g_pipelineArchive
          addComputePipelineFunctionsWithDescriptor:descriptor
                                              error:&populationError];
      if (!populated) {
        pipeline_archive_warning("compute population failed", populationError);
        // Never serialize an archive after Metal has rejected population.
        g_pipelineArchiveDirty = false;
        if (replace_pipeline_archive_with_empty(
                "compute population recovery archive creation failed")) {
          populationError = nil;
          populated = [g_pipelineArchive
              addComputePipelineFunctionsWithDescriptor:descriptor
                                                  error:&populationError];
          if (!populated) {
            pipeline_archive_warning(
                "compute population retry with empty archive failed",
                populationError);
          }
        }
      }
      if (populated) {
        descriptor.binaryArchives = @[ g_pipelineArchive ];
      }

      NSError *creationError = nil;
      id<MTLComputePipelineState> pipeline =
          [g_device newComputePipelineStateWithDescriptor:descriptor
                                                   options:MTLPipelineOptionNone
                                                reflection:nil
                                                     error:&creationError];
      if (pipeline && populated)
        g_pipelineArchiveDirty = true;
      if (!pipeline && populated) {
        pipeline_archive_warning("compute-assisted creation failed; retrying "
                                 "without the archive",
                                 creationError);
        descriptor.binaryArchives = nil;
        creationError = nil;
        pipeline =
            [g_device newComputePipelineStateWithDescriptor:descriptor
                                                    options:MTLPipelineOptionNone
                                                 reflection:nil
                                                      error:&creationError];
        // Keep the successful uncached pipeline, but rebuild the in-memory
        // archive so the assisted-creation failure cannot poison future runs.
        g_pipelineArchiveDirty = false;
        if (pipeline &&
            replace_pipeline_archive_with_empty(
                "compute-assisted recovery archive creation failed")) {
          NSError *repopulationError = nil;
          BOOL repopulated = [g_pipelineArchive
              addComputePipelineFunctionsWithDescriptor:descriptor
                                                  error:&repopulationError];
          if (repopulated) {
            g_pipelineArchiveDirty = true;
          } else {
            pipeline_archive_warning(
                "compute-assisted recovery population failed",
                repopulationError);
          }
        }
      }
      [descriptor release];
      if (outErr)
        *outErr = creationError;
      return pipeline;
    }
    id<MTLComputePipelineState> pipeline =
        [g_device newComputePipelineStateWithDescriptor:descriptor
                                                options:MTLPipelineOptionNone
                                             reflection:nil
                                                  error:outErr];
    [descriptor release];
    return pipeline;
  }
  return [g_device newComputePipelineStateWithFunction:func error:outErr];
}

static void serialize_pipeline_archive_atomically() {
  if (@available(macOS 11.0, *)) {
    if (!g_pipelineArchive || !g_archivePath)
      return;

    std::lock_guard<std::mutex> lock(g_pipelineArchiveMutex);
    if (!g_pipelineArchiveDirty)
      return;

    // Metal writes a complete archive to a sibling temporary path. POSIX
    // rename then replaces the prior cache in one filesystem operation, so a
    // crash or serialization failure leaves the known-old file untouched.
    NSString *temporaryPath = [NSString
        stringWithFormat:@"%@.tmp.%@", g_archivePath,
                         [[NSUUID UUID] UUIDString]];
    NSURL *temporaryURL = [NSURL fileURLWithPath:temporaryPath];
    NSError *serializationError = nil;
    BOOL serialized =
        [g_pipelineArchive serializeToURL:temporaryURL
                                   error:&serializationError];
    if (!serialized) {
      pipeline_archive_warning("serialization failed", serializationError);
      [[NSFileManager defaultManager] removeItemAtPath:temporaryPath
                                                error:nil];
      return;
    }

    int renameResult =
        rename([temporaryPath fileSystemRepresentation],
               [g_archivePath fileSystemRepresentation]);
    if (renameResult != 0) {
      pipeline_archive_warning("atomic replace failed", std::strerror(errno));
      [[NSFileManager defaultManager] removeItemAtPath:temporaryPath
                                                error:nil];
      return;
    }

    g_pipelineArchiveDirty = false;
    dbg("Pipeline archive saved atomically to: %s\n",
        [g_archivePath UTF8String]);
  }
}

// Metal 4 uses MTL4Compiler + MTL4PipelineDataSetSerializer rather than
// MTLBinaryArchive. This cache creates and retains translated Iris pipeline
// objects, but it is intentionally separate from every command encoder: Stage
// 6 cannot submit a draw.
static id g_irisMetal4PipelineSerializer = nil;
static id g_irisMetal4Compiler = nil;
static NSMutableArray *g_irisMetal4LookupArchives = nil;
static NSString *g_irisMetal4ArchivePath = nil;
static std::mutex g_irisMetal4PipelineCacheMutex;
// MTL4 command submission and its short-lived resource setup are serialized
// across Iris' translator and render threads.  The macOS 26 driver may route
// small shared buffers through one device suballocator; allowing the shadow,
// cutover and graph paths to churn that allocator concurrently is not a safe
// ownership boundary.
static std::mutex g_irisMetal4ExecutionMutex;
static bool iris_metal4_retired_submission_slot_available()
    API_AVAILABLE(macos(26.0));
static bool g_irisMetal4PipelineCacheDirty = false;
static bool g_irisMetal4ArchiveLoaded = false;
static constexpr NSUInteger kIrisMetal4ArchiveSegmentLimit = 32;
static std::atomic<uint64_t> g_irisMetal4PipelineAttemptCount{0};
static std::atomic<uint64_t> g_irisMetal4PipelineCompileCount{0};
static std::atomic<uint64_t> g_irisMetal4PipelineCacheHitCount{0};
static std::atomic<uint64_t> g_irisMetal4PipelineFailureCount{0};
static std::atomic<uint64_t> g_irisMetal4PipelineStaleRecoveryCount{0};
static std::atomic<uint64_t> g_irisMetal4PipelineDrawAttemptCount{0};
static thread_local std::vector<uint8_t> g_irisMetal4LastReplayRgba8;
static thread_local std::vector<uint8_t> g_irisMetal4LastGraphFrameRgba8;
static thread_local IOSurfaceRef g_irisMetal4PendingCutoverSurface = nullptr;
static thread_local uint32_t g_irisMetal4PendingCutoverWidth = 0;
static thread_local uint32_t g_irisMetal4PendingCutoverHeight = 0;
static std::atomic<uint64_t> g_irisMetal4CutoverSurfaceSequence{1};
static constexpr size_t kIrisMetal4CutoverBindingLimit = 3;

struct IrisMetal4GraphPresentation {
  uint64_t token = 0;
  uint32_t width = 0;
  uint32_t height = 0;
  IOSurfaceRef surface = nullptr;
  std::shared_ptr<Metal4ProbeState> feedbackState;
};

static constexpr size_t kIrisMetal4GraphPresentationLimit = 3;
static std::vector<IrisMetal4GraphPresentation>
    g_irisMetal4GraphPresentations;

struct IrisMetal4ReusableGraphSurface {
  uint32_t width = 0;
  uint32_t height = 0;
  IOSurfaceRef surface = nullptr;
};

static std::vector<IrisMetal4ReusableGraphSurface>
    g_irisMetal4ReusableGraphSurfaces;

static IOSurfaceRef iris_metal4_acquire_graph_surface(
    uint32_t width, uint32_t height) {
  auto found = std::find_if(g_irisMetal4ReusableGraphSurfaces.begin(),
      g_irisMetal4ReusableGraphSurfaces.end(),
      [&](const IrisMetal4ReusableGraphSurface &candidate) {
        return candidate.surface && candidate.width == width &&
               candidate.height == height;
      });
  if (found == g_irisMetal4ReusableGraphSurfaces.end())
    return nullptr;
  IOSurfaceRef result = found->surface;
  found->surface = nullptr;
  g_irisMetal4ReusableGraphSurfaces.erase(found);
  return result;
}

static bool iris_metal4_recycle_graph_surface(
    IOSurfaceRef surface, uint32_t width, uint32_t height) {
  if (!surface || width == 0 || height == 0 ||
      g_irisMetal4ReusableGraphSurfaces.size() >=
          kIrisMetal4GraphPresentationLimit) {
    return false;
  }
  try {
    if (g_irisMetal4ReusableGraphSurfaces.capacity() <
        kIrisMetal4GraphPresentationLimit) {
      g_irisMetal4ReusableGraphSurfaces.reserve(
          kIrisMetal4GraphPresentationLimit);
    }
    g_irisMetal4ReusableGraphSurfaces.push_back({width, height, surface});
    return true;
  } catch (...) {
    return false;
  }
}

static void reset_iris_metal4_graph_surface_pool() {
  for (IrisMetal4ReusableGraphSurface &slot :
       g_irisMetal4ReusableGraphSurfaces) {
    if (slot.surface)
      CFRelease(slot.surface);
  }
  g_irisMetal4ReusableGraphSurfaces.clear();
}

static void iris_metal4_atomic_max(std::atomic<uint64_t> &destination,
                                   uint64_t value) {
  uint64_t current = destination.load(std::memory_order_relaxed);
  while (current < value && !destination.compare_exchange_weak(
      current, value, std::memory_order_relaxed,
      std::memory_order_relaxed)) {
  }
}

static uint64_t iris_metal4_seconds_to_ns(CFTimeInterval seconds) {
  if (!std::isfinite(seconds) || seconds <= 0.0)
    return 0;
  constexpr double kMaximum =
      (double)std::numeric_limits<uint64_t>::max();
  double nanoseconds = seconds * 1'000'000'000.0;
  if (nanoseconds >= kMaximum)
    return std::numeric_limits<uint64_t>::max();
  return (uint64_t)nanoseconds;
}

static MTL4CommitOptions *iris_metal4_graph_timing_options()
    API_AVAILABLE(macos(26.0)) {
  MTL4CommitOptions *options = [[MTL4CommitOptions alloc] init];
  if (!options)
    return nil;
  CFTimeInterval submittedAt = CACurrentMediaTime();
  // Commit options are one-shot: Metal consumes the registered handler for a
  // single commit. The handler captures no C++ ownership state and only
  // updates lock-free counters, so delayed feedback cannot race allocator or
  // resource destruction.
  [options addFeedbackHandler:^(id<MTL4CommitFeedback> feedback) {
    NSError *error = feedback.error;
    if (error) {
      g_irisMetal4GraphFeedbackErrors.fetch_add(
          1, std::memory_order_relaxed);
    }
    CFTimeInterval start = feedback.GPUStartTime;
    CFTimeInterval end = feedback.GPUEndTime;
    uint64_t elapsedNs = iris_metal4_seconds_to_ns(end - start);
    if (elapsedNs == 0)
      return;
    g_irisMetal4GraphLastGpuNs.store(elapsedNs,
                                     std::memory_order_relaxed);
    g_irisMetal4GraphTotalGpuNs.fetch_add(elapsedNs,
                                          std::memory_order_relaxed);
    iris_metal4_atomic_max(g_irisMetal4GraphMaxGpuNs, elapsedNs);
    uint64_t queueNs = iris_metal4_seconds_to_ns(start - submittedAt);
    g_irisMetal4GraphLastQueueNs.store(queueNs,
                                       std::memory_order_relaxed);
    g_irisMetal4GraphTotalQueueNs.fetch_add(queueNs,
                                            std::memory_order_relaxed);
    iris_metal4_atomic_max(g_irisMetal4GraphMaxQueueNs, queueNs);
    if (g_irisMetal4GraphPerformanceSampling.load(
            std::memory_order_acquire)) {
      std::lock_guard<std::mutex> lock(
          g_irisMetal4GraphPerformanceMutex);
      if (!g_irisMetal4GraphPerformanceSampling.load(
              std::memory_order_relaxed)) {
        // A reset disabled sampling while this feedback callback waited.
      } else if (g_irisMetal4GraphPerformanceSamples.size() <
          kIrisMetal4GraphPerformanceSampleLimit) {
        g_irisMetal4GraphPerformanceSamples.push_back(elapsedNs);
      } else {
        g_irisMetal4GraphPerformanceDropped++;
      }
    }
    g_irisMetal4GraphGpuSamples.fetch_add(1, std::memory_order_release);
  }];
  return options;
}

class IrisMetal4GraphCpuTimer {
 public:
  explicit IrisMetal4GraphCpuTimer(bool enabled)
      : enabled_(enabled), startedAt_(enabled ? CACurrentMediaTime() : 0.0) {}

  ~IrisMetal4GraphCpuTimer() {
    if (!enabled_)
      return;
    uint64_t elapsedNs = iris_metal4_seconds_to_ns(
        CACurrentMediaTime() - startedAt_);
    if (elapsedNs == 0)
      return;
    g_irisMetal4GraphLastCpuNs.store(elapsedNs,
                                     std::memory_order_relaxed);
    g_irisMetal4GraphTotalCpuNs.fetch_add(elapsedNs,
                                          std::memory_order_relaxed);
    iris_metal4_atomic_max(g_irisMetal4GraphMaxCpuNs, elapsedNs);
    g_irisMetal4GraphCpuSamples.fetch_add(1, std::memory_order_release);
  }

 private:
  bool enabled_;
  CFTimeInterval startedAt_;
};

struct IrisMetal4GraphCpuProfile {
  uint64_t samples = 0;
  uint64_t parseNs = 0;
  uint64_t inputsNs = 0;
  uint64_t drawsNs = 0;
  uint64_t setupNs = 0;
  uint64_t presentationNs = 0;
  uint64_t residencyCreateNs = 0;
  uint64_t residencyPopulateNs = 0;
  uint64_t residencyCommitNs = 0;
  uint64_t commandNs = 0;
  uint64_t encodeNs = 0;
  uint64_t commitNs = 0;
  uint64_t retireNs = 0;
  uint64_t residencyRawAllocations = 0;
  uint64_t residencyUniqueAllocations = 0;
  uint64_t drawOperations = 0;
  uint64_t drawPasses = 0;
  uint64_t barrierCalls = 0;
};

static IrisMetal4GraphCpuProfile g_irisMetal4GraphCpuProfile;
static std::mutex g_irisMetal4GraphCpuProfileMutex;

static void iris_metal4_record_graph_cpu_profile(
    CFTimeInterval started, CFTimeInterval parsed,
    CFTimeInterval inputsReady, CFTimeInterval drawsReady,
    CFTimeInterval presentationReady, CFTimeInterval residencyCreated,
    CFTimeInterval residencyPopulated, CFTimeInterval residencyCommitted,
    CFTimeInterval setupReady, CFTimeInterval encoded,
    CFTimeInterval committed, CFTimeInterval retired,
    uint64_t residencyRawAllocations,
    uint64_t residencyUniqueAllocations,
    uint64_t drawOperations, uint64_t drawPasses,
    uint64_t barrierCalls) {
  if (!(started > 0.0 && parsed >= started && inputsReady >= parsed &&
        drawsReady >= inputsReady && presentationReady >= drawsReady &&
        residencyCreated >= presentationReady &&
        residencyPopulated >= residencyCreated &&
        residencyCommitted >= residencyPopulated &&
        setupReady >= residencyCommitted && encoded >= setupReady &&
        committed >= encoded && retired >= committed)) {
    return;
  }
  std::lock_guard<std::mutex> lock(g_irisMetal4GraphCpuProfileMutex);
  IrisMetal4GraphCpuProfile &profile = g_irisMetal4GraphCpuProfile;
  profile.samples++;
  profile.parseNs += iris_metal4_seconds_to_ns(parsed - started);
  profile.inputsNs += iris_metal4_seconds_to_ns(inputsReady - parsed);
  profile.drawsNs += iris_metal4_seconds_to_ns(drawsReady - inputsReady);
  profile.setupNs += iris_metal4_seconds_to_ns(setupReady - drawsReady);
  profile.presentationNs += iris_metal4_seconds_to_ns(
      presentationReady - drawsReady);
  profile.residencyCreateNs += iris_metal4_seconds_to_ns(
      residencyCreated - presentationReady);
  profile.residencyPopulateNs += iris_metal4_seconds_to_ns(
      residencyPopulated - residencyCreated);
  profile.residencyCommitNs += iris_metal4_seconds_to_ns(
      residencyCommitted - residencyPopulated);
  profile.commandNs += iris_metal4_seconds_to_ns(
      setupReady - residencyCommitted);
  profile.encodeNs += iris_metal4_seconds_to_ns(encoded - setupReady);
  profile.commitNs += iris_metal4_seconds_to_ns(committed - encoded);
  profile.retireNs += iris_metal4_seconds_to_ns(retired - committed);
  profile.residencyRawAllocations += residencyRawAllocations;
  profile.residencyUniqueAllocations += residencyUniqueAllocations;
  profile.drawOperations += drawOperations;
  profile.drawPasses += drawPasses;
  profile.barrierCalls += barrierCalls;
}

static void reset_iris_metal4_graph_presentations() {
  for (IrisMetal4GraphPresentation &presentation :
       g_irisMetal4GraphPresentations) {
    if (presentation.surface)
      CFRelease(presentation.surface);
  }
  g_irisMetal4GraphPresentations.clear();
  reset_iris_metal4_graph_surface_pool();
}

struct IrisMetal4CutoverGlBinding {
  IOSurfaceRef surface = nullptr;
  GLsync completionFence = nullptr;
  uint32_t width = 0;
  uint32_t height = 0;
};

static thread_local std::unordered_map<GLuint, IrisMetal4CutoverGlBinding>
    g_irisMetal4CutoverGlBindings;

static constexpr size_t kIrisMetal4InputHandoffLimit = 72;
static constexpr uint64_t kIrisMetal4InputHandoffByteLimit =
    256ULL * 1024ULL * 1024ULL;
static constexpr size_t kIrisMetal4InputSurfaceHandoffLimit = 256;
static constexpr uint64_t kIrisMetal4InputSurfaceByteLimit =
    512ULL * 1024ULL * 1024ULL;
struct IrisMetal4InputHandoff {
  uint64_t token = 0;
  uint64_t sourceGeneration = 0;
  uint64_t captureSerial = 0;
  uint64_t allocationBytes = 0;
  uint32_t kind = 0;
  uint32_t width = 0;
  uint32_t height = 0;
  GLuint sourceTexture = 0;
  GLuint rectangleTexture = 0;
  GLuint readFramebuffer = 0;
  GLuint drawFramebuffer = 0;
  GLsync copyFence = nullptr;
  bool copyReady = false;
  bool leased = false;
  IOSurfaceRef surface = nullptr;
  id<MTLTexture> metalTexture = nil;
  std::shared_ptr<Metal4ProbeState> inFlightFeedback;
};

enum class IrisMetal4InputHandoffReadiness {
  READY,
  MISSING_FENCE,
  FENCE_TIMEOUT,
};

static IrisMetal4InputHandoffReadiness
iris_metal4_ensure_input_handoff_ready(IrisMetal4InputHandoff &handoff) {
  if (handoff.kind == 3 || handoff.copyReady)
    return IrisMetal4InputHandoffReadiness::READY;
  if (!handoff.copyFence)
    return IrisMetal4InputHandoffReadiness::MISSING_FENCE;
  GLenum copyStatus = glClientWaitSync(handoff.copyFence,
      GL_SYNC_FLUSH_COMMANDS_BIT, 5ULL * NSEC_PER_SEC);
  if (copyStatus != GL_ALREADY_SIGNALED &&
      copyStatus != GL_CONDITION_SATISFIED) {
    return IrisMetal4InputHandoffReadiness::FENCE_TIMEOUT;
  }
  glDeleteSync(handoff.copyFence);
  handoff.copyFence = nullptr;
  // Multiple draws in one MGF9 frame may sample the same IOSurface snapshot.
  // The fence is a one-time transition into READY, not a per-draw permit.
  handoff.copyReady = true;
  return IrisMetal4InputHandoffReadiness::READY;
}
// Immutable resident uploads are generation keyed by the GL texture name.
// Dynamic IOSurface captures use a separate token-keyed ring so a translator
// worker can consume frame N while the render thread starts capturing frame
// N+1 without either thread observing an overwritten surface.
static std::unordered_map<GLuint, IrisMetal4InputHandoff>
    g_irisMetal4InputHandoffs;
static std::unordered_map<uint64_t, IrisMetal4InputHandoff>
    g_irisMetal4InputSurfaceHandoffs;
static std::unordered_map<GLuint, uint64_t>
    g_irisMetal4InputSurfaceCaptureSequences;
static uint64_t g_irisMetal4InputHandoffBytes = 0;
static uint64_t g_irisMetal4InputSurfaceBytes = 0;
static std::atomic<uint64_t> g_irisMetal4InputHandoffSequence{1};

static IrisMetal4InputHandoff *iris_metal4_find_input_handoff(
    GLuint sourceTexture, uint64_t token) {
  auto resident = g_irisMetal4InputHandoffs.find(sourceTexture);
  if (resident != g_irisMetal4InputHandoffs.end() &&
      resident->second.token == token) {
    return &resident->second;
  }
  auto surface = g_irisMetal4InputSurfaceHandoffs.find(token);
  if (surface != g_irisMetal4InputSurfaceHandoffs.end() &&
      surface->second.sourceTexture == sourceTexture) {
    return &surface->second;
  }
  return nullptr;
}

static bool iris_metal4_track_input_surface_lease(
    IrisMetal4InputHandoff &handoff, std::vector<uint64_t> &leases) {
  if (handoff.kind == 3)
    return true;
  if (!handoff.leased)
    return false;
  if (std::find(leases.begin(), leases.end(), handoff.token) ==
      leases.end()) {
    leases.push_back(handoff.token);
  }
  return true;
}

static void iris_metal4_finish_input_surface_leases(
    const std::vector<uint64_t> &leases,
    const std::shared_ptr<Metal4ProbeState> &feedback) {
  for (uint64_t token : leases) {
    auto found = g_irisMetal4InputSurfaceHandoffs.find(token);
    if (found == g_irisMetal4InputSurfaceHandoffs.end())
      continue;
    found->second.leased = false;
    found->second.inFlightFeedback = feedback;
  }
}

static constexpr size_t kIrisMetal4ResidentBufferLimit = 256;
static constexpr uint64_t kIrisMetal4ResidentBufferByteLimit =
    64ULL * 1024ULL * 1024ULL;
struct IrisMetal4ResidentBuffer {
  uint64_t token = 0;
  uint32_t byteLength = 0;
  id<MTLBuffer> buffer = nil;
};
static std::unordered_map<std::string,
    IrisMetal4ResidentBuffer> g_irisMetal4ResidentBuffers;
static std::unordered_map<uint64_t, IrisMetal4ResidentBuffer>
    g_irisMetal4ResidentBuffersByToken;
static uint64_t g_irisMetal4ResidentBufferBytes = 0;
static std::atomic<uint64_t> g_irisMetal4ResidentBufferSequence{1};

static id<MTLBuffer> iris_metal4_resident_buffer(
    uint64_t token, uint32_t byteLength) {
  auto indexed = g_irisMetal4ResidentBuffersByToken.find(token);
  if (indexed != g_irisMetal4ResidentBuffersByToken.end() &&
      indexed->second.byteLength == byteLength && indexed->second.buffer) {
    return indexed->second.buffer;
  }
  // Preserve fail-open compatibility with caches created before the index was
  // populated. Normal Stage 9 frames take the O(1) branch above.
  for (const auto &resident : g_irisMetal4ResidentBuffers) {
    if (resident.second.token == token &&
        resident.second.byteLength == byteLength &&
        resident.second.buffer) {
      return resident.second.buffer;
    }
  }
  return nil;
}

static constexpr size_t kIrisMetal4GraphTextureLimit = 512;
static constexpr uint64_t kIrisMetal4GraphTextureByteLimit =
    2ULL * 1024ULL * 1024ULL * 1024ULL;
static constexpr uint64_t kIrisMetal4GraphTextureSingleByteLimit =
    512ULL * 1024ULL * 1024ULL;

struct IrisMetal4GraphTextureKey {
  uint64_t contextGeneration = 0;
  uint32_t glName = 0;
  uint64_t resourceGeneration = 0;

  bool operator==(const IrisMetal4GraphTextureKey &other) const {
    return contextGeneration == other.contextGeneration &&
           glName == other.glName &&
           resourceGeneration == other.resourceGeneration;
  }
};

struct IrisMetal4GraphTextureKeyHash {
  size_t operator()(const IrisMetal4GraphTextureKey &key) const {
    uint64_t hash = key.contextGeneration ^
        (key.resourceGeneration + 0x9e3779b97f4a7c15ULL +
         (key.contextGeneration << 6) + (key.contextGeneration >> 2));
    hash ^= (uint64_t)key.glName + 0x9e3779b97f4a7c15ULL +
            (hash << 6) + (hash >> 2);
    return (size_t)hash;
  }
};

struct IrisMetal4GraphTexture {
  uint64_t token = 0;
  MTLPixelFormat pixelFormat = MTLPixelFormatInvalid;
  uint32_t sampleCount = 0;
  uint32_t width = 0;
  uint32_t height = 0;
  uint32_t depthOrLayers = 0;
  uint32_t mipLevels = 0;
  uint32_t usage = 0;
  uint64_t allocatedBytes = 0;
  id<MTLTexture> texture = nil;
};

static std::unordered_map<IrisMetal4GraphTextureKey,
    IrisMetal4GraphTexture, IrisMetal4GraphTextureKeyHash>
    g_irisMetal4GraphTextures;
static uint64_t g_irisMetal4GraphTextureBytes = 0;
static std::mutex g_irisMetal4GraphTextureMutex;
static std::atomic<uint64_t> g_irisMetal4GraphTextureSequence{1};

static void reset_iris_metal4_graph_resources() {
  reset_iris_metal4_graph_presentations();
  g_irisMetal4GraphPerformanceSampling.store(false,
                                              std::memory_order_release);
  {
    std::lock_guard<std::mutex> performanceLock(
        g_irisMetal4GraphPerformanceMutex);
    g_irisMetal4GraphPerformanceSamples.clear();
    g_irisMetal4GraphPerformanceDropped = 0;
  }
  std::lock_guard<std::mutex> lock(g_irisMetal4GraphTextureMutex);
  for (auto &entry : g_irisMetal4GraphTextures) {
    if (entry.second.texture)
      [entry.second.texture release];
  }
  g_irisMetal4GraphTextures.clear();
  g_irisMetal4GraphTextureBytes = 0;
}

struct IrisMetal4PipelineEntry {
  id<MTLRenderPipelineState> render = nil;
  id<MTLComputePipelineState> compute = nil;
  id<MTLFunction> vertexFunction = nil;
  id<MTLFunction> fragmentFunction = nil;
  id<MTLFunction> computeFunction = nil;
  id<MTLDepthStencilState> depthStencil = nil;
  std::vector<MTLPixelFormat> colorFormats;
  MTLPixelFormat depthFormat = MTLPixelFormatInvalid;
  MTLPixelFormat stencilFormat = MTLPixelFormatInvalid;
  NSUInteger rasterSampleCount = 1;
  MTLCullMode cullMode = MTLCullModeNone;
  MTLWinding frontFacingWinding = MTLWindingCounterClockwise;
  MTLTriangleFillMode triangleFillMode = MTLTriangleFillModeFill;
  MTLDepthClipMode depthClipMode = MTLDepthClipModeClip;
  NSUInteger sampleMask = ~(NSUInteger)0;
  float depthBias = 0.0f;
  float slopeScale = 0.0f;
  float depthBiasClamp = 0.0f;
  uint32_t polygonOffsetMask = 0;
  uint32_t frontStencilReference = 0;
  uint32_t backStencilReference = 0;
  uint32_t topology = 8;
  uint32_t restartMode = 0;
  uint32_t patchControlPoints = 0;
};
static std::unordered_map<std::string, IrisMetal4PipelineEntry>
    g_irisMetal4Pipelines;
static std::unordered_map<std::string, id<MTLLibrary>>
    g_irisMetal4Libraries;
static std::atomic<uint64_t> g_irisMetal4ArgumentEncoderGeneration{1};
// Retain the exact descriptors used to populate the Metal 4 serializer.  A
// successfully written archive is not considered complete until every one of
// these descriptors can be reconstructed from the read-only archive API.
// This also gives us a bounded way to repair a driver archive that accepted a
// flush but omitted an individual pipeline binary.
static NSMutableArray *g_irisMetal4RenderArchiveDescriptors = nil;
static NSMutableArray *g_irisMetal4ComputeArchiveDescriptors = nil;

static NSString *iris_metal4_archive_integrity_path(NSString *path) {
  return [path stringByAppendingString:@".integrity"];
}

static NSString *iris_metal4_archive_segment_path(NSString *basePath,
                                                   NSUInteger index) {
  if (!basePath || index == 0 || index > kIrisMetal4ArchiveSegmentLimit)
    return nil;
  return [basePath stringByAppendingFormat:@".segment-%02lu",
      (unsigned long)index];
}

static bool iris_metal4_archive_signature(NSString *path,
                                          NSString **signature) {
  if (!path || !signature)
    return false;
  NSError *attributeError = nil;
  NSDictionary *attributes = [[NSFileManager defaultManager]
      attributesOfItemAtPath:path error:&attributeError];
  unsigned long long length = attributes.fileSize;
  if (attributeError || length < 64 || length > 1024ULL * 1024ULL * 1024ULL)
    return false;
  NSError *readError = nil;
  NSData *data = [NSData dataWithContentsOfFile:path
                                       options:NSDataReadingMappedIfSafe
                                         error:&readError];
  if (!data || readError || data.length != length)
    return false;
  const uint8_t *bytes = (const uint8_t *)data.bytes;
  if (!bytes || bytes[0] != 0xcb || bytes[1] != 0xfe ||
      bytes[2] != 0xba || bytes[3] != 0xbe)
    return false;
  uint64_t hash = 1469598103934665603ULL;
  for (NSUInteger index = 0; index < data.length; index++) {
    hash ^= bytes[index];
    hash *= 1099511628211ULL;
  }
  *signature = [NSString stringWithFormat:@"v1:%llu:%016llx\n", length,
      (unsigned long long)hash];
  return true;
}

static bool iris_metal4_archive_integrity_matches(NSString *path) {
  NSString *actual = nil;
  if (!iris_metal4_archive_signature(path, &actual))
    return false;
  NSError *error = nil;
  NSString *expected = [NSString
      stringWithContentsOfFile:iris_metal4_archive_integrity_path(path)
                      encoding:NSASCIIStringEncoding error:&error];
  return expected && !error && [expected isEqualToString:actual];
}

static bool write_iris_metal4_archive_integrity(NSString *path) {
  NSString *signature = nil;
  if (!iris_metal4_archive_signature(path, &signature))
    return false;
  NSError *error = nil;
  BOOL written = [signature
      writeToFile:iris_metal4_archive_integrity_path(path)
        atomically:YES encoding:NSASCIIStringEncoding error:&error];
  if (!written)
    pipeline_archive_warning("Metal 4 archive integrity write failed", error);
  return written;
}

static void reset_iris_metal4_pipeline_cache_locked() {
  g_irisMetal4ArgumentEncoderGeneration.fetch_add(
      1, std::memory_order_acq_rel);
  for (auto &entry : g_irisMetal4Pipelines) {
    if (entry.second.render)
      [entry.second.render release];
    if (entry.second.compute)
      [entry.second.compute release];
    if (entry.second.vertexFunction)
      [entry.second.vertexFunction release];
    if (entry.second.fragmentFunction)
      [entry.second.fragmentFunction release];
    if (entry.second.computeFunction)
      [entry.second.computeFunction release];
    if (entry.second.depthStencil)
      [entry.second.depthStencil release];
  }
  g_irisMetal4Pipelines.clear();
  for (auto &library : g_irisMetal4Libraries) {
    if (library.second)
      [library.second release];
  }
  g_irisMetal4Libraries.clear();
  if (g_irisMetal4RenderArchiveDescriptors) {
    [g_irisMetal4RenderArchiveDescriptors release];
    g_irisMetal4RenderArchiveDescriptors = nil;
  }
  if (g_irisMetal4ComputeArchiveDescriptors) {
    [g_irisMetal4ComputeArchiveDescriptors release];
    g_irisMetal4ComputeArchiveDescriptors = nil;
  }
  if (g_irisMetal4LookupArchives) {
    [g_irisMetal4LookupArchives release];
    g_irisMetal4LookupArchives = nil;
  }
  if (g_irisMetal4Compiler) {
    [g_irisMetal4Compiler release];
    g_irisMetal4Compiler = nil;
  }
  if (g_irisMetal4PipelineSerializer) {
    [g_irisMetal4PipelineSerializer release];
    g_irisMetal4PipelineSerializer = nil;
  }
  if (g_irisMetal4ArchivePath) {
    [g_irisMetal4ArchivePath release];
    g_irisMetal4ArchivePath = nil;
  }
  g_irisMetal4PipelineCacheDirty = false;
  g_irisMetal4ArchiveLoaded = false;
  g_irisMetal4PipelineAttemptCount.store(0, std::memory_order_relaxed);
  g_irisMetal4PipelineCompileCount.store(0, std::memory_order_relaxed);
  g_irisMetal4PipelineCacheHitCount.store(0, std::memory_order_relaxed);
  g_irisMetal4PipelineFailureCount.store(0, std::memory_order_relaxed);
  g_irisMetal4PipelineStaleRecoveryCount.store(0,
                                               std::memory_order_relaxed);
  g_irisMetal4PipelineDrawAttemptCount.store(0,
                                              std::memory_order_relaxed);
}

static bool quarantine_stale_iris_metal4_archive_locked(NSString *path) {
  if (!path || ![[NSFileManager defaultManager] fileExistsAtPath:path])
    return true;
  NSString *rejected = [path stringByAppendingString:@".rejected"];
  [[NSFileManager defaultManager] removeItemAtPath:rejected error:nil];
  NSError *moveError = nil;
  BOOL moved = [[NSFileManager defaultManager] moveItemAtPath:path
                                                       toPath:rejected
                                                        error:&moveError];
  if (!moved) {
    pipeline_archive_warning("Metal 4 stale archive quarantine failed",
                             moveError);
    return false;
  }
  NSString *integrity = iris_metal4_archive_integrity_path(path);
  if ([[NSFileManager defaultManager] fileExistsAtPath:integrity]) {
    NSString *rejectedIntegrity =
        iris_metal4_archive_integrity_path(rejected);
    [[NSFileManager defaultManager] removeItemAtPath:rejectedIntegrity
                                               error:nil];
    [[NSFileManager defaultManager] moveItemAtPath:integrity
                                            toPath:rejectedIntegrity
                                             error:nil];
  }
  g_irisMetal4PipelineStaleRecoveryCount.fetch_add(
      1, std::memory_order_relaxed);
  return true;
}

// Must be called while g_irisMetal4PipelineCacheMutex is held.
static int prepare_iris_metal4_pipeline_cache_for_translated_msl_locked(
    NSString *archivePath) {
  if (!g_device ||
      !g_metal4RuntimeVerified.load(std::memory_order_acquire))
    return 0;
  if (@available(macOS 26.0, *)) {
    if (!archivePath || ![archivePath isAbsolutePath])
      return -1;
    if (g_irisMetal4Compiler && g_irisMetal4PipelineSerializer &&
        [g_irisMetal4ArchivePath isEqualToString:archivePath])
      return g_irisMetal4ArchiveLoaded ? 2 : 1;

    reset_iris_metal4_pipeline_cache_locked();

    if (![g_device
            respondsToSelector:
                @selector(newPipelineDataSetSerializerWithDescriptor:)] ||
        ![g_device respondsToSelector:@selector(newCompilerWithDescriptor:
                                                       error:)]) {
      return 0;
    }

    MTL4PipelineDataSetSerializerDescriptor *serializerDescriptor =
        [[MTL4PipelineDataSetSerializerDescriptor alloc] init];
    serializerDescriptor.configuration =
        MTL4PipelineDataSetSerializerConfigurationCaptureBinaries;
    id<MTL4PipelineDataSetSerializer> serializer =
        [g_device
            newPipelineDataSetSerializerWithDescriptor:serializerDescriptor];
    [serializerDescriptor release];
    if (!serializer)
      return -1;

    MTL4CompilerDescriptor *compilerDescriptor =
        [[MTL4CompilerDescriptor alloc] init];
    compilerDescriptor.label =
        @"MetalRender translated Iris MSL pipeline compiler";
    compilerDescriptor.pipelineDataSetSerializer = serializer;
    NSError *compilerError = nil;
    id<MTL4Compiler> compiler =
        [g_device newCompilerWithDescriptor:compilerDescriptor
                                      error:&compilerError];
    [compilerDescriptor release];
    if (!compiler) {
      pipeline_archive_warning("Metal 4 translated-pipeline compiler setup "
                               "failed",
                               compilerError);
      [serializer release];
      return -1;
    }

    g_irisMetal4ArchivePath = [archivePath copy];
    bool staleRecovered = false;
    bool fatalArchiveFailure = false;
    NSMutableArray *lookupArchives = [[NSMutableArray alloc]
        initWithCapacity:kIrisMetal4ArchiveSegmentLimit + 1];
    NSMutableArray *archivePaths = [NSMutableArray arrayWithObject:
        g_irisMetal4ArchivePath];
    for (NSUInteger index = 1;
         index <= kIrisMetal4ArchiveSegmentLimit; index++) {
      [archivePaths addObject:iris_metal4_archive_segment_path(
          g_irisMetal4ArchivePath, index)];
    }
    for (NSString *candidatePath in archivePaths) {
      if (![[NSFileManager defaultManager]
              fileExistsAtPath:candidatePath]) {
        continue;
      }
      if (!iris_metal4_archive_integrity_matches(candidatePath)) {
        pipeline_archive_warning(
            "Metal 4 translated-pipeline archive failed integrity preflight; "
            "quarantining without entering the driver", "integrity mismatch");
        bool recovered = quarantine_stale_iris_metal4_archive_locked(
            candidatePath);
        staleRecovered |= recovered;
        fatalArchiveFailure |= !recovered;
      } else {
        NSError *archiveError = nil;
        id<MTL4Archive> lookupArchive =
            [g_device
                newArchiveWithURL:
                    [NSURL fileURLWithPath:candidatePath]
                             error:&archiveError];
        if (lookupArchive) {
          [lookupArchives addObject:lookupArchive];
          [lookupArchive release];
        } else {
          pipeline_archive_warning("Metal 4 translated-pipeline archive load "
                                   "failed; quarantining stale cache",
                                   archiveError);
          bool recovered = quarantine_stale_iris_metal4_archive_locked(
              candidatePath);
          staleRecovered |= recovered;
          fatalArchiveFailure |= !recovered;
        }
      }
    }
    if (fatalArchiveFailure) {
      pipeline_archive_warning("Metal 4 translated-pipeline archive load "
                               "preflight/quarantine failed", "fatal");
      [lookupArchives release];
      [compiler release];
      [serializer release];
      [g_irisMetal4ArchivePath release];
      g_irisMetal4ArchivePath = nil;
      return -1;
    }
    g_irisMetal4LookupArchives = lookupArchives;
    g_irisMetal4ArchiveLoaded = lookupArchives.count > 0;

    g_irisMetal4PipelineSerializer = serializer;
    g_irisMetal4Compiler = compiler;
    return staleRecovered ? 3 : (g_irisMetal4ArchiveLoaded ? 2 : 1);
  }
  return 0;
}

// Must be called while g_irisMetal4PipelineCacheMutex is held.
static bool serialize_iris_metal4_pipeline_cache_after_translated_builds_locked() {
  if (@available(macOS 26.0, *)) {
    if (!g_irisMetal4PipelineSerializer || !g_irisMetal4ArchivePath)
      return false;
    if (!g_irisMetal4PipelineCacheDirty) {
      return g_irisMetal4ArchiveLoaded;
    }

    NSString *outputPath = nil;
    if (![[NSFileManager defaultManager]
            fileExistsAtPath:g_irisMetal4ArchivePath]) {
      outputPath = g_irisMetal4ArchivePath;
    } else {
      for (NSUInteger index = 1;
           index <= kIrisMetal4ArchiveSegmentLimit; index++) {
        NSString *candidate = iris_metal4_archive_segment_path(
            g_irisMetal4ArchivePath, index);
        if (![[NSFileManager defaultManager]
                fileExistsAtPath:candidate]) {
          outputPath = candidate;
          break;
        }
      }
    }
    if (!outputPath) {
      pipeline_archive_warning(
          "Metal 4 translated-pipeline archive segment limit reached",
          "cache full");
      return false;
    }

    NSString *temporaryPath = [NSString
        stringWithFormat:@"%@.tmp.%@", outputPath,
                         [[NSUUID UUID] UUIDString]];
    NSError *serializationError = nil;
    BOOL serialized =
        [(id<MTL4PipelineDataSetSerializer>)g_irisMetal4PipelineSerializer
            serializeAsArchiveAndFlushToURL:
                [NSURL fileURLWithPath:temporaryPath]
                                      error:&serializationError];
    if (!serialized) {
      pipeline_archive_warning("Metal 4 translated-pipeline serialization "
                               "failed",
                               serializationError);
      [[NSFileManager defaultManager] removeItemAtPath:temporaryPath
                                                error:nil];
      return false;
    }

    int renameResult =
        rename([temporaryPath fileSystemRepresentation],
               [outputPath fileSystemRepresentation]);
    if (renameResult != 0) {
      pipeline_archive_warning("Metal 4 translated-pipeline atomic segment "
                               "publish failed",
                               std::strerror(errno));
      [[NSFileManager defaultManager] removeItemAtPath:temporaryPath
                                                error:nil];
      return false;
    }
    NSError *validationError = nil;
    id<MTL4Archive> replacement = [g_device
        newArchiveWithURL:[NSURL fileURLWithPath:outputPath]
                    error:&validationError];
    if (!replacement) {
      pipeline_archive_warning("Metal 4 translated-pipeline segment "
                               "validation failed",
                               validationError);
      quarantine_stale_iris_metal4_archive_locked(
          outputPath);
      return false;
    }
    if (!write_iris_metal4_archive_integrity(outputPath)) {
      [replacement release];
      quarantine_stale_iris_metal4_archive_locked(outputPath);
      return false;
    }
    if (!g_irisMetal4LookupArchives) {
      g_irisMetal4LookupArchives = [[NSMutableArray alloc] init];
    }
    [g_irisMetal4LookupArchives addObject:replacement];
    [replacement release];
    g_irisMetal4ArchiveLoaded = true;
    g_irisMetal4PipelineCacheDirty = false;

    // Validate the published archive through the same read-only lookup API
    // used on the next launch.  Metal 4 archives are opaque, so a successful
    // serialize call alone cannot prove that every harvested pipeline is
    // present.  If a descriptor is missing, recreate only that pipeline with
    // the serializer still attached and publish a bounded repair segment.
    NSUInteger repaired = 0;
    auto archiveContains = [&](id descriptor, bool compute) {
      for (id archive in g_irisMetal4LookupArchives) {
        NSError *lookupError = nil;
        id pipeline = compute
            ? (id)[(id<MTL4Archive>)archive
                newComputePipelineStateWithDescriptor:
                    (MTL4ComputePipelineDescriptor *)descriptor
                                                 error:&lookupError]
            : (id)[(id<MTL4Archive>)archive
                newRenderPipelineStateWithDescriptor:
                    (MTL4RenderPipelineDescriptor *)descriptor
                                                error:&lookupError];
        if (pipeline) {
          [pipeline release];
          return true;
        }
      }
      return false;
    };
    auto captureMissing = [&](id descriptor, bool compute) {
      MTL4CompilerTaskOptions *taskOptions =
          [[MTL4CompilerTaskOptions alloc] init];
      if (g_irisMetal4LookupArchives.count > 0)
        taskOptions.lookupArchives = g_irisMetal4LookupArchives;
      NSError *compileError = nil;
      id pipeline = compute
          ? (id)[(id<MTL4Compiler>)g_irisMetal4Compiler
              newComputePipelineStateWithDescriptor:
                  (MTL4ComputePipelineDescriptor *)descriptor
                               compilerTaskOptions:taskOptions
                                             error:&compileError]
          : (id)[(id<MTL4Compiler>)g_irisMetal4Compiler
              newRenderPipelineStateWithDescriptor:
                  (MTL4RenderPipelineDescriptor *)descriptor
                              compilerTaskOptions:taskOptions
                                            error:&compileError];
      [taskOptions release];
      if (!pipeline) {
        pipeline_archive_warning(
            "Metal 4 archive repair pipeline build failed", compileError);
        return false;
      }
      [pipeline release];
      repaired++;
      return true;
    };
    for (id descriptor in g_irisMetal4RenderArchiveDescriptors) {
      if (!archiveContains(descriptor, false) &&
          !captureMissing(descriptor, false)) {
        return false;
      }
    }
    for (id descriptor in g_irisMetal4ComputeArchiveDescriptors) {
      if (!archiveContains(descriptor, true) &&
          !captureMissing(descriptor, true)) {
        return false;
      }
    }
    if (repaired > 0) {
      static thread_local NSUInteger repairDepth = 0;
      if (repairDepth >= 2) {
        pipeline_archive_warning(
            "Metal 4 archive remained incomplete after repair", "fatal");
        return false;
      }
      dbg("Metal 4 archive verification captured %lu missing pipeline(s) "
          "into a repair segment\n", (unsigned long)repaired);
      g_irisMetal4PipelineCacheDirty = true;
      repairDepth++;
      bool repairPublished =
          serialize_iris_metal4_pipeline_cache_after_translated_builds_locked();
      repairDepth--;
      return repairPublished;
    }
    return true;
  }
  return false;
}

static void load_shaders() {
  dbg("load_shaders() called: device=%p shaderLibrary=%p\n", g_device,
      g_shaderLibrary);
  if (!g_device || g_shaderLibrary)
    return;
  NSError *error = nil;

  if (@available(macOS 11.0, *)) {
    NSArray *caches = NSSearchPathForDirectoriesInDomains(
        NSCachesDirectory, NSUserDomainMask, YES);
    NSString *cacheDir =
        (caches.count > 0) ? caches[0] : NSTemporaryDirectory();
    g_archivePath = [[cacheDir
        stringByAppendingPathComponent:@"metalrender_pipeline_cache.metallib"]
        copy];
    MTLBinaryArchiveDescriptor *archDesc =
        [[MTLBinaryArchiveDescriptor alloc] init];
    BOOL existingArchive =
        [[NSFileManager defaultManager] fileExistsAtPath:g_archivePath];
    if (existingArchive) {
      archDesc.url = [NSURL fileURLWithPath:g_archivePath];
      dbg("Loading pipeline cache from: %s\n", [g_archivePath UTF8String]);
    }
    NSError *archErr = nil;
    g_pipelineArchive = [g_device newBinaryArchiveWithDescriptor:archDesc
                                                           error:&archErr];
    [archDesc release];
    if (!g_pipelineArchive && existingArchive) {
      pipeline_archive_warning(
          "load failed; preserving the old file and retrying empty", archErr);
      NSError *emptyArchiveError = nil;
      g_pipelineArchive =
          new_empty_pipeline_archive(&emptyArchiveError);
      if (!g_pipelineArchive) {
        pipeline_archive_warning("empty fallback creation failed",
                                 emptyArchiveError);
      } else {
        dbg("Pipeline cache recovery is using an empty in-memory archive; "
            "the old file stays untouched until a complete replacement is "
            "serialized\n");
      }
    } else if (!g_pipelineArchive) {
      pipeline_archive_warning("empty archive creation failed", archErr);
    }
  }

  NSMutableArray<NSString *> *searchPaths = [NSMutableArray array];
  Dl_info dlInfo;
  if (dladdr((void *)load_shaders, &dlInfo) && dlInfo.dli_fname) {
    NSString *dylibPath = [[NSString stringWithUTF8String:dlInfo.dli_fname]
        stringByDeletingLastPathComponent];
    NSString *metallibPath =
        [dylibPath stringByAppendingPathComponent:@"shaders.metallib"];
    // A packaged shader library is extracted beside this dylib. Prefer that
    // checksum-paired payload over any development file in the working tree.
    [searchPaths addObject:metallibPath];
  }
  [searchPaths addObjectsFromArray:@[
    @"src/main/resources/shaders.metallib",
    [NSString
        stringWithFormat:@"%@/shaders.metallib",
                         [[NSFileManager defaultManager] currentDirectoryPath]],
    @"shaders.metallib",
  ]];
  for (NSString *path in searchPaths) {
    if ([[NSFileManager defaultManager] fileExistsAtPath:path]) {
      NSURL *url = [NSURL fileURLWithPath:path];
      g_shaderLibrary = [g_device newLibraryWithURL:url error:&error];
      if (g_shaderLibrary) {
        dbg("Loaded metallib from: %s\n", [path UTF8String]);
        break;
      } else {
        dbg("Failed to load metallib from %s: %s\n", [path UTF8String],
            error ? [[error localizedDescription] UTF8String] : "unknown");
      }
    }
  }
  if (!g_shaderLibrary) {
    dbg("No metallib found, falling back to inline shader compilation\n");
    NSString *shaderSource = @R"(
#include <metal_stdlib>
using namespace metal;
struct InhouseTerrainVertex {
	packed_short3 position;
	packed_ushort2 texCoord;
	packed_uchar4 color;
	uchar packedLight;
	uchar normalIndex;
};
struct SimpleVertexOut {
	float4 position  [[position]];
	float2 texCoord;
	half4 color;
	half light;
	float3 worldPos;
	uint normalIdx [[flat]];
	half fogDist;
};
vertex SimpleVertexOut vertex_terrain_inhouse(
	device const InhouseTerrainVertex* vertices   [[buffer(0)]],
	constant float4x4& projectionMatrix            [[buffer(1)]],
	constant float4x4& modelViewMatrix            [[buffer(2)]],
	constant float4& cameraPosition               [[buffer(3)]],
	constant float4& chunkOffset                  [[buffer(4)]],
	uint vid [[vertex_id]]
) {
	InhouseTerrainVertex v = vertices[vid];
	SimpleVertexOut out;
	uint faceMask = as_type<uint>(chunkOffset.w);
	if (faceMask != 0 && v.color[3] == 255) {
		uint nIdx = v.normalIndex & 0x7;
		if (nIdx < 6 && ((faceMask >> nIdx) & 1) == 0) {
			out.position = float4(0.0, 0.0, -2.0, 1.0);
			out.texCoord = float2(0.0);
			out.color    = half4(0.0h);
			out.light    = 0.0h;
			return out;
		}
	}
	float3 localPos = float3(short3(v.position)) / 256.0;
	float3 worldPos = localPos + chunkOffset.xyz;
	float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
	out.position = projectionMatrix * viewPos;
	out.texCoord = float2(v.texCoord) / 65535.0;
	out.color    = half4(float4(v.color) / 255.0);
	float blockLight = float(v.packedLight & 0xF) / 15.0;
	float skyLight   = float((v.packedLight >> 4) & 0xF) / 15.0;
	out.light    = half(max(blockLight, skyLight * cameraPosition.w));
	out.worldPos = worldPos;
	out.normalIdx = v.normalIndex & 0x7;
	out.fogDist = half(length(viewPos.xyz));
	return out;
}
fragment float4 fragment_terrain(
	SimpleVertexOut in [[stage_in]],
	texture2d<float> blockAtlas [[texture(0)]],
	constant float4& overlayParams [[buffer(5)]]
) {
	constexpr sampler s(filter::nearest, address::clamp_to_edge);
	float4 texColor = blockAtlas.sample(s, in.texCoord);
	float ca = float(in.color.a);
	if (texColor.a < 0.5) {

		if (ca > 0.993 && ca < 0.999) {
			texColor.a = 1.0;
		} else {
			discard_fragment();
		}
	}
	constant float faceShade[6] = { 0.5, 1.0, 0.8, 0.8, 0.6, 0.6 };
	float shade = (in.normalIdx < 6) ? faceShade[in.normalIdx] : 1.0;
	float4 baseColor = texColor * float4(in.color);
	baseColor.rgb *= shade;
	baseColor.rgb *= max(float(in.light), 0.04f);
	float waterFog = overlayParams.z;
	if (waterFog > 0.0) {
		float fogFactor = clamp(float(in.fogDist) / 48.0, 0.0, 0.85);
		baseColor.rgb = mix(baseColor.rgb, float3(0.05, 0.12, 0.3), fogFactor * waterFog);
	}
	float outAlpha = ca > 0.98 ? 1.0 : ca;
	return float4(baseColor.rgb, outAlpha);
}
struct FragmentArgs {
	texture2d<float> blockAtlas [[id(0)]];
};
fragment float4 fragment_terrain_icb(
	SimpleVertexOut in [[stage_in]],
	constant FragmentArgs& args [[buffer(0)]],
	constant float4& overlayParams [[buffer(5)]]
) {
	constexpr sampler s(filter::nearest, address::clamp_to_edge);
	float4 texColor = args.blockAtlas.sample(s, in.texCoord);
	float ca = float(in.color.a);
	if (texColor.a < 0.5) {
		if (ca > 0.993 && ca < 0.999) {
			texColor.a = 1.0;
		} else {
			discard_fragment();
		}
	}
	constant float faceShade[6] = { 0.5, 1.0, 0.8, 0.8, 0.6, 0.6 };
	float shade = (in.normalIdx < 6) ? faceShade[in.normalIdx] : 1.0;
	float4 baseColor = texColor * float4(in.color);
	baseColor.rgb *= shade;
	baseColor.rgb *= max(float(in.light), 0.04f);
	float waterFog = overlayParams.z;
	if (waterFog > 0.0) {
		float fogFactor = clamp(float(in.fogDist) / 48.0, 0.0, 0.85);
		baseColor.rgb = mix(baseColor.rgb, float3(0.05, 0.12, 0.3), fogFactor * waterFog);
	}
	float outAlpha = ca > 0.98 ? 1.0 : ca;
	return float4(baseColor.rgb, outAlpha);
}



struct DepthOnlyOut {
	float4 position [[position]];
};
vertex DepthOnlyOut vertex_depth_only(
	device const InhouseTerrainVertex* vertices   [[buffer(0)]],
	constant float4x4& projectionMatrix            [[buffer(1)]],
	constant float4x4& modelViewMatrix            [[buffer(2)]],
	constant float4& cameraPosition               [[buffer(3)]],
	constant float4& chunkOffset                  [[buffer(4)]],
	uint vid [[vertex_id]]
) {
	InhouseTerrainVertex v = vertices[vid];
	DepthOnlyOut out;
	uint faceMask = as_type<uint>(chunkOffset.w);
	if (faceMask != 0 && v.color[3] == 255) {
		uint nIdx = v.normalIndex & 0x7;
		if (nIdx < 6 && ((faceMask >> nIdx) & 1) == 0) {
			out.position = float4(0.0, 0.0, -2.0, 1.0);
			return out;
		}
	}
	float3 localPos = float3(short3(v.position)) / 256.0;
	float3 worldPos = localPos + chunkOffset.xyz;
	float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
	out.position = projectionMatrix * viewPos;
	return out;
}

struct EntityVertex {
	packed_float3 position;
	packed_short2 texCoord;
	packed_uchar4 color;
	packed_uchar4 normal;
	packed_short2 overlay;
	packed_short2 lightUV;
};
struct EntityVertexOut {
	float4 position  [[position]];
	float2 texCoord;
	float4 color;
	float3 normal;
	float2 lightUV;
	float2 overlay;
	float3 worldPos;
	float fogDist;
};
vertex EntityVertexOut vertex_entity(
	device const EntityVertex*     vertices    [[buffer(0)]],
	constant float4x4&             projection  [[buffer(1)]],
	constant float4x4&             modelView   [[buffer(2)]],
	uint vid [[vertex_id]]
) {
	EntityVertex v = vertices[vid];
	EntityVertexOut out;
	float3 pos      = float3(v.position);
	float4 viewPos  = modelView * float4(pos, 1.0);
	out.position = projection * viewPos;
	out.texCoord = float2(v.texCoord) / 32768.0;
	out.color    = float4(v.color) / 255.0;



	float3 rawN = float3(v.normal.xyz);
	out.normal = normalize(float3(
		rawN.x > 127.0f ? rawN.x - 256.0f : rawN.x,
		rawN.y > 127.0f ? rawN.y - 256.0f : rawN.y,
		rawN.z > 127.0f ? rawN.z - 256.0f : rawN.z
	) / 127.0f);
	out.lightUV  = float2(v.lightUV) / 256.0;
	out.overlay  = float2(v.overlay.x, v.overlay.y);
	out.worldPos = pos;
	out.fogDist  = length(viewPos.xyz);
	return out;
}
fragment float4 fragment_entity(
	EntityVertexOut in [[stage_in]],
	texture2d<float> entityTex  [[texture(0)]],
	constant float4& overlayParams [[buffer(5)]]
) {
	constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
	float4 texColor = entityTex.sample(texSampler, in.texCoord);

	if (texColor.a < 0.1f) discard_fragment();
	float4 baseColor = texColor * in.color;
	float3 lightDir = normalize(float3(0.2, 1.0, 0.5));
	float nDotL     = max(dot(in.normal, lightDir), 0.0);
	baseColor.rgb *= (0.4 + 0.6 * nDotL);
	float blockLight = clamp(in.lightUV.x, 0.0, 1.0);

	float skyLight   = clamp(in.lightUV.y * overlayParams.w, 0.0, 1.0);
	baseColor.rgb *= max(max(blockLight, skyLight), 0.5);
	float hurtTime = overlayParams.x;
	if (hurtTime > 0.0) {
		baseColor.rgb = mix(baseColor.rgb, float3(1.0, 0.0, 0.0),
			clamp(hurtTime, 0.0, 0.6));
	}
	float whiteFlash = overlayParams.y;
	if (whiteFlash > 0.0) {
		baseColor.rgb = mix(baseColor.rgb, float3(1.0),
			clamp(whiteFlash, 0.0, 1.0));
	}

	float waterFog = overlayParams.z;
	if (waterFog > 0.0) {
		float dist = in.fogDist;
		float fogFactor = clamp(dist / 32.0, 0.0, 0.85);
		baseColor.rgb = mix(baseColor.rgb, float3(0.05, 0.12, 0.3), fogFactor);
		baseColor.a = mix(1.0, 0.3, fogFactor);
	}
	return float4(baseColor.rgb, baseColor.a);
}
fragment float4 fragment_entity_translucent(
	EntityVertexOut in [[stage_in]],
	texture2d<float> entityTex  [[texture(0)]],
	constant float4& overlayParams [[buffer(5)]]
) {
	constexpr sampler texSampler(filter::linear, address::clamp_to_edge);
	float4 texColor = entityTex.sample(texSampler, in.texCoord);
	if (texColor.a < 0.004f) discard_fragment();
	float4 baseColor = texColor * in.color;
	float3 lightDir = normalize(float3(0.2, 1.0, 0.5));
	float nDotL     = max(dot(in.normal, lightDir), 0.0);
	baseColor.rgb *= (0.4 + 0.6 * nDotL);
	float blockLight = clamp(in.lightUV.x, 0.0, 1.0);
	float skyLight   = clamp(in.lightUV.y * overlayParams.w, 0.0, 1.0);
	baseColor.rgb *= max(max(blockLight, skyLight), 0.5);
	float hurtTime = overlayParams.x;
	if (hurtTime > 0.0) {
		baseColor.rgb = mix(baseColor.rgb, float3(1.0, 0.0, 0.0),
			clamp(hurtTime, 0.0, 0.6));
	}

	float waterFog = overlayParams.z;
	if (waterFog > 0.0) {
		float dist = in.fogDist;
		float fogFactor = clamp(dist / 32.0, 0.0, 0.85);
		baseColor.rgb = mix(baseColor.rgb, float3(0.05, 0.12, 0.3), fogFactor);
		baseColor.a *= (1.0 - fogFactor * 0.4);
	}
	return baseColor;
}
fragment float4 fragment_entity_emissive(
	EntityVertexOut in [[stage_in]],
	texture2d<float> entityTex  [[texture(0)]]
) {
	constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
	float4 texColor = entityTex.sample(texSampler, in.texCoord);
	float4 baseColor = (texColor.a > 0.01) ? texColor * in.color : in.color;
	return baseColor;
}
fragment float4 fragment_particle(
	EntityVertexOut in [[stage_in]],
	texture2d<float> entityTex  [[texture(0)]],
	constant float4& overlayParams [[buffer(5)]]
) {

	constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
	float4 texColor = entityTex.sample(texSampler, in.texCoord);


	if (texColor.a < 0.01) discard_fragment();
	float4 baseColor = texColor * in.color;


	float blockLight = clamp(in.lightUV.x, 0.0, 1.0);
	float skyLight   = clamp(in.lightUV.y * overlayParams.w, 0.0, 1.0);
	baseColor.rgb *= max(max(blockLight, skyLight), 0.3);

	float waterFog = overlayParams.z;
	if (waterFog > 0.0) {
		float dist = in.fogDist;
		float fogFactor = clamp(dist / 24.0, 0.0, 0.85);
		baseColor.rgb = mix(baseColor.rgb, float3(0.05, 0.12, 0.3), fogFactor);
		baseColor.a *= (1.0 - fogFactor * 0.5);
	}
	return baseColor;
}
	)";
    MTLCompileOptions *opts = [[MTLCompileOptions alloc] init];
    g_shaderLibrary = [g_device newLibraryWithSource:shaderSource
                                             options:opts
                                               error:&error];
    if (!g_shaderLibrary) {
      dbg("FATAL: Shader compilation failed: %s\n",
          error ? [[error localizedDescription] UTF8String] : "unknown");
      return;
    }
    dbg("Inline shader compilation OK\n");
  }
  id<MTLFunction> vertexFn =
      [g_shaderLibrary newFunctionWithName:@"vertex_terrain_inhouse"];
  id<MTLFunction> fragmentFn =
      [g_shaderLibrary newFunctionWithName:@"fragment_terrain"];
  if (vertexFn && fragmentFn) {
    MTLRenderPipelineDescriptor *desc =
        [[MTLRenderPipelineDescriptor alloc] init];
    desc.vertexFunction = vertexFn;
    desc.fragmentFunction = fragmentFn;
    desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    desc.colorAttachments[0].blendingEnabled = YES;
    desc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
    desc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
    desc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
    desc.colorAttachments[0].destinationRGBBlendFactor =
        MTLBlendFactorOneMinusSourceAlpha;
    desc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
    desc.colorAttachments[0].destinationAlphaBlendFactor =
        MTLBlendFactorOneMinusSourceAlpha;
    desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    desc.label = @"InhouseTerrain";
    g_pipelineInhouse = makePipeline(desc, &error);
    if (!g_pipelineInhouse) {
      dbg("FATAL: Terrain pipeline creation failed: %s\n",
          [[error localizedDescription] UTF8String]);
    }
    id<MTLFunction> waterFragFn =
        [g_shaderLibrary newFunctionWithName:@"fragment_water_surface"];
    if (vertexFn && waterFragFn) {
      MTLRenderPipelineDescriptor *waterDesc =
          [[MTLRenderPipelineDescriptor alloc] init];
      waterDesc.vertexFunction = vertexFn;
      waterDesc.fragmentFunction = waterFragFn;
      waterDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      waterDesc.colorAttachments[0].blendingEnabled = YES;
      waterDesc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
      waterDesc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
      waterDesc.colorAttachments[0].sourceRGBBlendFactor =
          MTLBlendFactorSourceAlpha;
      waterDesc.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      waterDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      waterDesc.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      waterDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      waterDesc.label = @"WaterSurface";
      g_pipelineWater = makePipeline(waterDesc, &error);
      if (!g_pipelineWater) {
        dbg("WARN: Water surface pipeline creation failed: %s\n",
            error ? [[error localizedDescription] UTF8String] : "unknown");
      }
    }
    g_pipelineOpaque = g_pipelineInhouse;
    id<MTLFunction> fragIcbFn =
        [g_shaderLibrary newFunctionWithName:@"fragment_terrain_icb"];
    if (vertexFn && fragIcbFn) {
      MTLRenderPipelineDescriptor *icbDesc =
          [[MTLRenderPipelineDescriptor alloc] init];
      icbDesc.vertexFunction = vertexFn;
      icbDesc.fragmentFunction = fragIcbFn;
      icbDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      icbDesc.colorAttachments[0].blendingEnabled = YES;
      icbDesc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
      icbDesc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
      icbDesc.colorAttachments[0].sourceRGBBlendFactor =
          MTLBlendFactorSourceAlpha;
      icbDesc.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      icbDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      icbDesc.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      icbDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      icbDesc.supportIndirectCommandBuffers = YES;
      icbDesc.label = @"InhouseTerrainICB";
      g_pipelineInhouseICB = makePipeline(icbDesc, &error);
      if (!g_pipelineInhouseICB) {
        dbg("WARN: ICB terrain pipeline creation failed: %s\n",
            [[error localizedDescription] UTF8String]);
      } else {
        dbg("ICB terrain pipeline created OK\n");
        g_fragArgEncoder = [fragIcbFn newArgumentEncoderWithBufferIndex:0];
        if (g_fragArgEncoder) {
          NSUInteger argBufLen = [g_fragArgEncoder encodedLength];
          bool allArgumentBuffersAllocated = true;
          for (int slot = 0; slot < kTripleBufferCount; slot++) {
            g_fragArgBuf[slot] =
                [g_device newBufferWithLength:argBufLen
                                      options:MTLStorageModeShared];
            allArgumentBuffersAllocated &= g_fragArgBuf[slot] != nil;
          }
          g_icbCapable = allArgumentBuffersAllocated;
          dbg("Fragment arg buffer: %zu bytes\n", (size_t)argBufLen);
        }
      }
    } else {
      dbg("WARN: ICB fragment shader not found\n");
    }
    id<MTLFunction> fragIcbOpaqueFn =
        [g_shaderLibrary newFunctionWithName:@"fragment_terrain_icb_opaque"];
    if (vertexFn && fragIcbOpaqueFn) {
      MTLRenderPipelineDescriptor *opaqueIcbDesc =
          [[MTLRenderPipelineDescriptor alloc] init];
      opaqueIcbDesc.vertexFunction = vertexFn;
      opaqueIcbDesc.fragmentFunction = fragIcbOpaqueFn;
      opaqueIcbDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      opaqueIcbDesc.colorAttachments[0].blendingEnabled = NO;
      opaqueIcbDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      opaqueIcbDesc.supportIndirectCommandBuffers = YES;
      opaqueIcbDesc.label = @"InhouseTerrainICBOpaque";
      g_pipelineInhouseICBOpaque = makePipeline(opaqueIcbDesc, &error);
      if (g_pipelineInhouseICBOpaque) {
        dbg("ICB opaque terrain pipeline created OK\n");
        g_fragArgEncoderOpaque =
            [fragIcbOpaqueFn newArgumentEncoderWithBufferIndex:0];
        if (g_fragArgEncoderOpaque) {
          NSUInteger argBufLen = [g_fragArgEncoderOpaque encodedLength];
          for (int slot = 0; slot < kTripleBufferCount; slot++) {
            g_fragArgBufOpaque[slot] =
                [g_device newBufferWithLength:argBufLen
                                      options:MTLStorageModeShared];
          }
        }
      } else {
        dbg("WARN: ICB opaque pipeline failed: %s\n",
            error ? [[error localizedDescription] UTF8String] : "unknown");
      }
    }
    id<MTLFunction> fragOpaqueFn =
        [g_shaderLibrary newFunctionWithName:@"fragment_terrain_opaque"];
    if (vertexFn && fragOpaqueFn) {
      MTLRenderPipelineDescriptor *opaqueDesc =
          [[MTLRenderPipelineDescriptor alloc] init];
      opaqueDesc.vertexFunction = vertexFn;
      opaqueDesc.fragmentFunction = fragOpaqueFn;
      opaqueDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      opaqueDesc.colorAttachments[0].blendingEnabled = NO;
      opaqueDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      opaqueDesc.label = @"InhouseTerrainOpaque";
      g_pipelineInhouseOpaque = makePipeline(opaqueDesc, &error);
      if (g_pipelineInhouseOpaque) {
        dbg("Opaque terrain pipeline created OK\n");
      } else {
        dbg("WARN: Opaque pipeline failed: %s\n",
            error ? [[error localizedDescription] UTF8String] : "unknown");
      }
    }
  }

  auto createEntityPipeline = [&](NSString *vertName, NSString *fragName,
                                  NSString *label,
                                  bool blending) -> id<MTLRenderPipelineState> {
    id<MTLFunction> vf = [g_shaderLibrary newFunctionWithName:vertName];
    id<MTLFunction> ff = [g_shaderLibrary newFunctionWithName:fragName];
    if (!vf || !ff) {
      dbg("Entity pipeline '%s': missing function (vert=%p frag=%p)\n",
          [label UTF8String], vf, ff);
      return nil;
    }
    MTLRenderPipelineDescriptor *pd =
        [[MTLRenderPipelineDescriptor alloc] init];
    pd.vertexFunction = vf;
    pd.fragmentFunction = ff;
    pd.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    pd.label = label;
    if (blending) {
      pd.colorAttachments[0].blendingEnabled = YES;
      pd.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
      pd.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
      pd.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
      pd.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      pd.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      pd.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
    } else {
      pd.colorAttachments[0].blendingEnabled = NO;
    }
    id<MTLRenderPipelineState> ps = makePipeline(pd, &error);
    if (!ps) {
      dbg("Entity pipeline '%s' creation failed: %s\n", [label UTF8String],
          error ? [[error localizedDescription] UTF8String] : "unknown");
    } else {
      dbg("Entity pipeline '%s' created OK\n", [label UTF8String]);
    }
    return ps;
  };
  g_pipelineEntity = createEntityPipeline(@"vertex_entity", @"fragment_entity",
                                          @"EntityOpaque", false);
  g_pipelineEntityTranslucent =
      createEntityPipeline(@"vertex_entity", @"fragment_entity_translucent",
                           @"EntityTranslucent", true);
  g_pipelineEntityEmissive = createEntityPipeline(
      @"vertex_entity", @"fragment_entity_emissive", @"EntityEmissive", true);
  g_pipelineEntityInstanced =
      createEntityPipeline(@"vertex_entity_instanced", @"fragment_entity",
                           @"EntityInstanced", false);
  id<MTLFunction> outlineFragFn =
      [g_shaderLibrary newFunctionWithName:@"fragment_entity_outline"];
  if (outlineFragFn) {
    g_pipelineEntityOutline = createEntityPipeline(
        @"vertex_entity", @"fragment_entity_outline", @"EntityOutline", true);
  }
  g_pipelineEntityShadow = createEntityPipeline(
      @"vertex_entity", @"fragment_entity_shadow", @"EntityShadow", true);
  g_pipelineParticle = createEntityPipeline(
      @"vertex_entity", @"fragment_particle", @"Particle", true);
  {
    id<MTLFunction> dbgVert =
        [g_shaderLibrary newFunctionWithName:@"vertex_debug"];
    id<MTLFunction> dbgFrag =
        [g_shaderLibrary newFunctionWithName:@"fragment_debug"];
    if (dbgVert && dbgFrag) {
      MTLRenderPipelineDescriptor *dbgDesc =
          [[MTLRenderPipelineDescriptor alloc] init];
      dbgDesc.vertexFunction = dbgVert;
      dbgDesc.fragmentFunction = dbgFrag;
      dbgDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      dbgDesc.colorAttachments[0].blendingEnabled = YES;
      dbgDesc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
      dbgDesc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
      dbgDesc.colorAttachments[0].sourceRGBBlendFactor =
          MTLBlendFactorSourceAlpha;
      dbgDesc.colorAttachments[0].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      dbgDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      dbgDesc.colorAttachments[0].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      dbgDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      dbgDesc.label = @"DebugLines";
      NSError *dbgErr = nil;
      g_pipelineDebugLines = makePipeline(dbgDesc, &dbgErr);
      if (!g_pipelineDebugLines) {
        dbg("Debug line pipeline creation failed: %s\n",
            dbgErr ? [[dbgErr localizedDescription] UTF8String] : "unknown");
      } else {
        dbg("Debug line pipeline created OK\n");
      }
    }
  }
  MTLDepthStencilDescriptor *dsDesc = [[MTLDepthStencilDescriptor alloc] init];
  // Minecraft 26.2 uses reversed-Z: near maps to 1, far maps to 0.
  dsDesc.depthCompareFunction = MTLCompareFunctionGreater;
  dsDesc.depthWriteEnabled = YES;
  g_depthState = [g_device newDepthStencilStateWithDescriptor:dsDesc];
  g_depthStateReversedZ = g_depthState;
  MTLDepthStencilDescriptor *dsNoWrite =
      [[MTLDepthStencilDescriptor alloc] init];
  dsNoWrite.depthCompareFunction = MTLCompareFunctionGreaterEqual;
  dsNoWrite.depthWriteEnabled = NO;
  g_depthStateNoWrite = [g_device newDepthStencilStateWithDescriptor:dsNoWrite];
  g_depthStateReversedZNoWrite = g_depthStateNoWrite;
  MTLDepthStencilDescriptor *dsLessEq =
      [[MTLDepthStencilDescriptor alloc] init];
  dsLessEq.depthCompareFunction = MTLCompareFunctionGreaterEqual;
  dsLessEq.depthWriteEnabled = NO;
  g_depthStateLessEqual =
      [g_device newDepthStencilStateWithDescriptor:dsLessEq];
  g_depthStateReversedZReadOnly = g_depthStateLessEqual;

  MTLDepthStencilDescriptor *dsEqNoWrite =
      [[MTLDepthStencilDescriptor alloc] init];
  dsEqNoWrite.depthCompareFunction = MTLCompareFunctionEqual;
  dsEqNoWrite.depthWriteEnabled = NO;
  g_depthStateEqualNoWrite =
      [g_device newDepthStencilStateWithDescriptor:dsEqNoWrite];
  [dsDesc release];
  [dsNoWrite release];
  [dsLessEq release];
  [dsEqNoWrite release];
  auto createComputePipeline =
      [&](NSString *funcName) -> id<MTLComputePipelineState> {
    id<MTLFunction> func = [g_shaderLibrary newFunctionWithName:funcName];
    if (!func) {
      dbg("Compute function '%s' not found in library\n",
          [funcName UTF8String]);
      return nil;
    }
    NSError *cErr = nil;
    id<MTLComputePipelineState> cps = makeComputePipeline(func, &cErr);
    if (!cps) {
      dbg("Compute pipeline '%s' creation failed: %s\n", [funcName UTF8String],
          cErr ? [[cErr localizedDescription] UTF8String] : "unknown");
    } else {
      dbg("Compute pipeline '%s' created OK\n", [funcName UTF8String]);
    }
    return cps;
  };
  g_hizDownsamplePipeline = createComputePipeline(@"hiz_downsample");
  g_hizMultiPipeline = createComputePipeline(@"hiz_downsample_multi");
  g_cullEncodePipeline = createComputePipeline(@"cull_and_encode");
  g_resetCullPipeline = createComputePipeline(@"reset_cull_stats");

  {
    NSError *oitErr = nil;
    id<MTLFunction> vtxTerrain =
        [g_shaderLibrary newFunctionWithName:@"vertex_terrain_inhouse"];
    id<MTLFunction> fragAccum =
        [g_shaderLibrary newFunctionWithName:@"fragment_oit_terrain_accum"];
    if (vtxTerrain && fragAccum) {
      MTLRenderPipelineDescriptor *ad =
          [[MTLRenderPipelineDescriptor alloc] init];
      ad.vertexFunction = vtxTerrain;
      ad.fragmentFunction = fragAccum;
      ad.label = @"OITAccum";

      ad.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
      ad.colorAttachments[0].blendingEnabled = YES;
      ad.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
      ad.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
      ad.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorOne;
      ad.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOne;
      ad.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      ad.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOne;

      ad.colorAttachments[1].pixelFormat = MTLPixelFormatR8Unorm;
      ad.colorAttachments[1].blendingEnabled = YES;
      ad.colorAttachments[1].rgbBlendOperation = MTLBlendOperationAdd;
      ad.colorAttachments[1].sourceRGBBlendFactor = MTLBlendFactorZero;
      ad.colorAttachments[1].destinationRGBBlendFactor =
          MTLBlendFactorOneMinusSourceColor;
      ad.colorAttachments[1].sourceAlphaBlendFactor = MTLBlendFactorZero;
      ad.colorAttachments[1].destinationAlphaBlendFactor =
          MTLBlendFactorOneMinusSourceAlpha;
      ad.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      g_pipelineOITAccum = makePipeline(ad, &oitErr);
      if (g_pipelineOITAccum)
        dbg("OIT accum pipeline created OK\n");
      else
        dbg("WARN: OIT accum pipeline failed: %s\n",
            oitErr ? [[oitErr localizedDescription] UTF8String] : "unknown");
    } else {
      dbg("WARN: OIT accum shaders not found (vertex=%s fragment=%s)\n",
          vtxTerrain ? "ok" : "missing", fragAccum ? "ok" : "missing");
    }
    id<MTLFunction> vtxComp =
        [g_shaderLibrary newFunctionWithName:@"vertex_oit_composite"];
    id<MTLFunction> fragComp =
        [g_shaderLibrary newFunctionWithName:@"fragment_oit_composite_tbdr"];
    if (vtxComp && fragComp) {
      MTLRenderPipelineDescriptor *cd =
          [[MTLRenderPipelineDescriptor alloc] init];
      cd.vertexFunction = vtxComp;
      cd.fragmentFunction = fragComp;
      cd.label = @"OITComposite";
      cd.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      cd.colorAttachments[0].blendingEnabled = NO;
      oitErr = nil;
      g_pipelineOITComposite = makePipeline(cd, &oitErr);
      if (g_pipelineOITComposite)
        dbg("OIT composite pipeline created OK\n");
      else
        dbg("WARN: OIT composite pipeline failed: %s\n",
            oitErr ? [[oitErr localizedDescription] UTF8String] : "unknown");
    } else {
      dbg("WARN: OIT composite shaders not found (vertex=%s fragment=%s)\n",
          vtxComp ? "ok" : "missing", fragComp ? "ok" : "missing");
    }
  }
  if (!g_frameSemaphore) {

    g_frameSemaphore = dispatch_semaphore_create(kTripleBufferCount - 1);
    dbg("Triple buffering: dispatch_semaphore created (count=%d)\n",
        kTripleBufferCount - 1);
  }
  if (!g_frameEvent) {
    g_frameEvent = [g_device newSharedEvent];
    g_eventListener = [[MTLSharedEventListener alloc]
        initWithDispatchQueue:dispatch_get_global_queue(
                                  QOS_CLASS_USER_INTERACTIVE, 0)];
    g_eventCounter = 0;
    dbg("Triple buffering: MTLSharedEvent created\n");
  }
  for (int i = 0; i < kTripleBufferCount; i++) {
    if (!g_tripleBuffers[i]) {
      g_tripleBuffers[i] = [g_device newBufferWithLength:512
                                                 options:MTLStorageModeShared];
    }
  }
  if (!g_cullDrawCountBuffer) {
    g_cullDrawCountBuffer = [g_device newBufferWithLength:sizeof(uint32_t)
                                                  options:MTLStorageModeShared];
  }
  if (!g_cullStatsBuffer) {
    g_cullStatsBuffer = [g_device newBufferWithLength:sizeof(uint32_t) * 8
                                              options:MTLStorageModeShared];
  }
  if (!g_cullDrawArgsBuffer) {
    size_t argsSize = g_maxGPUDrawCalls * sizeof(uint32_t);
    g_cullDrawArgsBuffer = [g_device newBufferWithLength:argsSize
                                                 options:MTLStorageModeShared];
  }
  if (!g_visibleIndicesBuffer) {
    g_visibleIndicesBuffer =
        [g_device newBufferWithLength:g_maxGPUDrawCalls * sizeof(uint32_t)
                              options:MTLStorageModeShared];
  }
  if (!g_blockAtlas) {
    MTLTextureDescriptor *fallbackDesc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                     width:1
                                    height:1
                                 mipmapped:NO];
    fallbackDesc.usage = MTLTextureUsageShaderRead;
    fallbackDesc.storageMode = MTLStorageModeShared;
    g_blockAtlas = [g_device newTextureWithDescriptor:fallbackDesc];
    uint8_t white[4] = {255, 255, 255, 255};
    [g_blockAtlas replaceRegion:MTLRegionMake2D(0, 0, 1, 1)
                    mipmapLevel:0
                      withBytes:white
                    bytesPerRow:4];
    dbg("Created 1x1 white fallback atlas texture\n");
  }

  serialize_pipeline_archive_atomically();
  dbg("Shaders loaded: terrain inhouse=%p opaque=%p entity=%p "
      "entityTranslucent=%p entityEmissive=%p depth=%p\n",
      g_pipelineInhouse, g_pipelineOpaque, g_pipelineEntity,
      g_pipelineEntityTranslucent, g_pipelineEntityEmissive, g_depthState);
}

static void reset_frame_semaphore_after_drain() {
  if (g_frameSemaphore) {
    dispatch_release(g_frameSemaphore);
    g_frameSemaphore = nil;
  }
  int maxInFlight =
      g_tripleBufferingEnabled.load(std::memory_order_acquire) ? 2 : 1;
  g_frameSemaphore = dispatch_semaphore_create(maxInFlight);
}

static void wait_for_staged_texture_uploads() {
  id<MTLCommandBuffer> uploadCommandBuffer = nil;
  {
    std::lock_guard<std::mutex> lock(g_textureUploadMutex);
    // Transfer the retained global reference to this stack scope.
    uploadCommandBuffer = g_lastTextureUploadCommandBuffer;
    g_lastTextureUploadCommandBuffer = nil;
  }
  if (!uploadCommandBuffer)
    return;
  if (uploadCommandBuffer.status < MTLCommandBufferStatusCompleted)
    [uploadCommandBuffer waitUntilCompleted];
  [uploadCommandBuffer release];
}

static void drain_surface_slots(bool finishOpenGL) {
  @autoreleasepool {
    if (g_currentEncoder) {
      [g_currentEncoder endEncoding];
      [g_currentEncoder release];
      g_currentEncoder = nil;
    }
    if (g_currentCmdBuffer) {
      [g_currentCmdBuffer commit];
      [g_currentCmdBuffer waitUntilCompleted];
      if (g_currentCmdBuffer.status != MTLCommandBufferStatusCompleted) {
        g_gpuCommandBufferErrorCount.fetch_add(
            1, std::memory_order_relaxed);
        g_gpuNeedsRecovery.store(true, std::memory_order_release);
      }
      [g_currentCmdBuffer release];
      g_currentCmdBuffer = nil;
    }
    if (finishOpenGL && CGLGetCurrentContext())
      glFinish();

    for (int i = 0; i < kTripleBufferCount; i++) {
      id<MTLCommandBuffer> commandBuffer = g_tbCmdBuf[i];
      if (commandBuffer &&
          commandBuffer.status < MTLCommandBufferStatusCompleted) {
        [commandBuffer waitUntilCompleted];
      }
    }
    // Texture uploads are separate same-queue blits. Waiting for the newest
    // one also orders every earlier upload, including during queue recovery.
    wait_for_staged_texture_uploads();
    {
      std::lock_guard<std::mutex> lock(g_surfaceSlotMutex);
      for (int i = 0; i < kTripleBufferCount; i++) {
        if (g_tbCmdBuf[i]) {
          [g_tbCmdBuf[i] release];
          g_tbCmdBuf[i] = nil;
        }
        g_tbSlotState[i].store(SurfaceSlotAvailable,
                               std::memory_order_release);
        g_tbSlotReady[i].store(true, std::memory_order_release);
      }
      g_glBoundSlot.store(-1, std::memory_order_release);
      g_tbLastCompleted.store(-1, std::memory_order_release);
      g_currentFrameReady.store(true, std::memory_order_release);
    }
    g_surfaceSlotChanged.notify_all();
    reset_frame_semaphore_after_drain();
  }
}

static int find_available_surface_slot_locked(int preferred) {
  int slotCount = std::max(
      2, std::min(kTripleBufferCount,
                  g_activeSurfaceSlots.load(std::memory_order_acquire)));
  for (int offset = 0; offset < slotCount; offset++) {
    int slot = (preferred + offset) % slotCount;
    if (g_tbSlotState[slot].load(std::memory_order_acquire) ==
        SurfaceSlotAvailable)
      return slot;
  }
  // Completed surfaces that were never handed to OpenGL are safe to drop.
  // Prefer an older ready frame over the latest completed one, but reclaim
  // the latest too when it is the only way to prevent ring starvation (for
  // example during startup or screenshot fail-open frames).
  int lastCompleted = g_tbLastCompleted.load(std::memory_order_acquire);
  for (int offset = 0; offset < slotCount; offset++) {
    int slot = (preferred + offset) % slotCount;
    if (slot != lastCompleted &&
        g_tbSlotState[slot].load(std::memory_order_acquire) ==
            SurfaceSlotReadyForPresentation)
      return slot;
  }
  if (lastCompleted >= 0 && lastCompleted < slotCount &&
      g_tbSlotState[lastCompleted].load(std::memory_order_acquire) ==
          SurfaceSlotReadyForPresentation)
    return lastCompleted;
  return -1;
}

static int acquire_surface_slot(int preferred) {
  std::unique_lock<std::mutex> lock(g_surfaceSlotMutex);
  auto available = [&] {
    return g_shuttingDown.load(std::memory_order_acquire) ||
           find_available_surface_slot_locked(preferred) >= 0;
  };
  if (!available()) {
    int waitMs = std::max(8, (int)ceilf(g_targetFrameTimeMs * 2.0f));
    g_surfaceSlotChanged.wait_for(lock, std::chrono::milliseconds(waitMs),
                                 available);
  }
  if (g_shuttingDown.load(std::memory_order_acquire))
    return -1;
  int slot = find_available_surface_slot_locked(preferred);
  if (slot < 0)
    return -1;
  if (g_tbSlotState[slot].load(std::memory_order_acquire) ==
      SurfaceSlotReadyForPresentation) {
    int replacementCompleted = -1;
    for (int i = 0; i < kTripleBufferCount; i++) {
      if (i != slot &&
          g_tbSlotState[i].load(std::memory_order_acquire) ==
              SurfaceSlotReadyForPresentation) {
        replacementCompleted = i;
      }
    }
    if (g_tbLastCompleted.load(std::memory_order_acquire) == slot)
      g_tbLastCompleted.store(replacementCompleted,
                              std::memory_order_release);
  }
  g_tbSlotState[slot].store(SurfaceSlotMetalInFlight,
                            std::memory_order_release);
  g_tbSlotReady[slot].store(false, std::memory_order_release);
  return slot;
}

static void ensure_offscreen() {
  if (!g_device)
    return;
  int outputW = std::max(1, g_rtWidth);
  int outputH = std::max(1, g_rtHeight);
  int renderW = std::max(1, (int)lroundf(outputW * g_scale));
  int renderH = std::max(1, (int)lroundf(outputH * g_scale));

  bool recreate = (!g_tbColor[0]) ||
                  ((int)g_tbColor[0].width != outputW) ||
                  ((int)g_tbColor[0].height != outputH) ||
                  g_allocatedRenderWidth != renderW ||
                  g_allocatedRenderHeight != renderH;
  if (!recreate)
    return;

  // IOSurfaces and depth targets may still be retained by either GPU commands
  // or the OpenGL presentation texture. Drain both APIs before replacement.
  drain_surface_slots(true);
  for (int s = 0; s < 3; s++) {
    if (g_tbColor[s]) {
      [g_tbColor[s] release];
      g_tbColor[s] = nil;
    }
    if (g_tbDepth[s]) {
      [g_tbDepth[s] release];
      g_tbDepth[s] = nil;
    }
    if (g_tbDepthReadBuffer[s]) {
      [g_tbDepthReadBuffer[s] release];
      g_tbDepthReadBuffer[s] = nil;
    }
    if (g_tbIOSurface[s]) {
      CFRelease(g_tbIOSurface[s]);
      g_tbIOSurface[s] = NULL;
    }
    g_tbSlotReady[s].store(true, std::memory_order_release);
    g_tbSlotState[s].store(SurfaceSlotAvailable, std::memory_order_release);
  }
  g_tbLastCompleted.store(-1, std::memory_order_release);
  g_color = nil;
  g_depth = nil;
  g_frameColorTarget = nil;
  g_frameDepthTarget = nil;
  g_ioSurface = NULL;
  if (g_hizPyramid) {
    [g_hizPyramid release];
    g_hizPyramid = nil;
  }
  for (int m = 0; m < 16; m++) {
    if (g_hizSrcViews[m]) {
      [g_hizSrcViews[m] release];
      g_hizSrcViews[m] = nil;
    }
    if (g_hizDstViews[m]) {
      [g_hizDstViews[m] release];
      g_hizDstViews[m] = nil;
    }
  }
  g_hizViewsValid = 0;
  // CGL and Metal share the same IOSurface allocation, so the row stride
  // must satisfy the strictest device in the system.  Hard-coded 16/64-byte
  // rounding is insufficient on Apple Silicon configurations where
  // kIOSurfaceBytesPerRow currently requires 128-byte alignment; an
  // undersized allocation lets a GPU row write corrupt the following heap
  // block.
  size_t bytesPerRow = IOSurfaceAlignProperty(
      kIOSurfaceBytesPerRow, (size_t)outputW * 4u);

  for (int s = 0; s < 3; s++) {
    NSDictionary *surfaceProperties = @{
      (id)kIOSurfaceWidth : @(outputW),
      (id)kIOSurfaceHeight : @(outputH),
      (id)kIOSurfaceBytesPerElement : @4,
      (id)kIOSurfaceBytesPerRow : @(bytesPerRow),
      (id)kIOSurfaceAllocSize : @(bytesPerRow * (size_t)outputH),
      (id)kIOSurfacePixelFormat : @((uint32_t)'BGRA'),
    };
    g_tbIOSurface[s] =
        IOSurfaceCreate((__bridge CFDictionaryRef)surfaceProperties);
    MTLTextureDescriptor *cd = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                     width:outputW
                                    height:outputH
                                 mipmapped:NO];
    cd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    cd.storageMode = MTLStorageModeShared;
    if (g_tbIOSurface[s]) {
      g_tbColor[s] = [g_device newTextureWithDescriptor:cd
                                              iosurface:g_tbIOSurface[s]
                                                  plane:0];
    }
    if (!g_tbColor[s]) {
      cd.storageMode = MTLStorageModeShared;
      g_tbColor[s] = [g_device newTextureWithDescriptor:cd];
    }
    MTLTextureDescriptor *dd = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
                                     width:outputW
                                    height:outputH
                                 mipmapped:NO];

    dd.storageMode = MTLStorageModePrivate;
    dd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    g_tbDepth[s] = [g_device newTextureWithDescriptor:dd];
  }
#ifdef METALRENDER_HAS_METALFX

  if (@available(macOS 13.0, *)) {
    [g_mfxScaler release];
    g_mfxScaler = nil;
    for (int s = 0; s < 3; s++) {
      if (g_lrColor[s]) {
        [g_lrColor[s] release];
        g_lrColor[s] = nil;
      }
      if (g_lrDepth[s]) {
        [g_lrDepth[s] release];
        g_lrDepth[s] = nil;
      }
      if (g_mfxOutput[s]) {
        [g_mfxOutput[s] release];
        g_mfxOutput[s] = nil;
      }
    }
    if (g_scale < 0.99f) {

      int nativeW = outputW;
      int nativeH = outputH;
      int lrw = renderW;
      int lrh = renderH;
      MTLFXSpatialScalerDescriptor *scalerDesc =
          [[MTLFXSpatialScalerDescriptor alloc] init];
      scalerDesc.inputWidth = (NSUInteger)lrw;
      scalerDesc.inputHeight = (NSUInteger)lrh;
      scalerDesc.outputWidth = (NSUInteger)nativeW;
      scalerDesc.outputHeight = (NSUInteger)nativeH;
      scalerDesc.colorTextureFormat = MTLPixelFormatBGRA8Unorm;
      scalerDesc.outputTextureFormat = MTLPixelFormatBGRA8Unorm;
      g_mfxScaler = [scalerDesc newSpatialScalerWithDevice:g_device];
      [scalerDesc release];
      if (!g_mfxScaler) {
        dbg("WARN: MTLFXSpatialScaler creation failed\n");
      } else {
        bool scalerTexturesReady = true;
        MTLTextureUsage inputUsage =
            MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead |
            g_mfxScaler.colorTextureUsage;
        MTLTextureUsage outputUsage = g_mfxScaler.outputTextureUsage;
        if (outputUsage == MTLTextureUsageUnknown)
          outputUsage = MTLTextureUsageShaderWrite;
        for (int s = 0; s < 3; s++) {
          MTLTextureDescriptor *inputDesc = [MTLTextureDescriptor
              texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                           width:lrw
                                          height:lrh
                                       mipmapped:NO];
          inputDesc.storageMode = MTLStorageModePrivate;
          inputDesc.usage = inputUsage;
          g_lrColor[s] = [g_device newTextureWithDescriptor:inputDesc];

          MTLTextureDescriptor *depthDesc = [MTLTextureDescriptor
              texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
                                           width:lrw
                                          height:lrh
                                       mipmapped:NO];
          depthDesc.storageMode = MTLStorageModePrivate;
          depthDesc.usage =
              MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
          g_lrDepth[s] = [g_device newTextureWithDescriptor:depthDesc];

          MTLTextureDescriptor *outputDesc = [MTLTextureDescriptor
              texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                           width:nativeW
                                          height:nativeH
                                       mipmapped:NO];
          outputDesc.storageMode = MTLStorageModePrivate;
          outputDesc.usage = outputUsage | MTLTextureUsageShaderRead;
          g_mfxOutput[s] = [g_device newTextureWithDescriptor:outputDesc];
          scalerTexturesReady &= g_lrColor[s] != nil;
          scalerTexturesReady &= g_lrDepth[s] != nil;
          scalerTexturesReady &= g_mfxOutput[s] != nil;
        }
        if (!scalerTexturesReady) {
          dbg("WARN: MetalFX private texture allocation failed; using "
              "full-res rendering\n");
          [g_mfxScaler release];
          g_mfxScaler = nil;
          for (int s = 0; s < 3; s++) {
            if (g_lrColor[s]) {
              [g_lrColor[s] release];
              g_lrColor[s] = nil;
            }
            if (g_lrDepth[s]) {
              [g_lrDepth[s] release];
              g_lrDepth[s] = nil;
            }
            if (g_mfxOutput[s]) {
              [g_mfxOutput[s] release];
              g_mfxOutput[s] = nil;
            }
          }
        }
      }
      if (g_mfxScaler)
        dbg("MetalFX SpatialScaler created: %dx%d -> %dx%d (scale=%.2f)\n", lrw,
            lrh, nativeW, nativeH, g_scale);
    } else {
      dbg("MetalFX upscaler DISABLED (scale=%.2f >= 1.0, full-res path)\n",
          g_scale);
    }
  }
#endif

  g_color = g_tbColor[0];
  g_depth = g_tbDepth[0];
  g_ioSurface = g_tbIOSurface[0];
  g_renderSlot = 0;
  g_allocatedRenderWidth = renderW;
  g_allocatedRenderHeight = renderH;
  dbg("Presentation targets: %dx%d; render targets: %dx%d (%d slots)\n",
      outputW, outputH, renderW, renderH,
      g_activeSurfaceSlots.load(std::memory_order_relaxed));

  g_hizWidth = renderW;
  g_hizHeight = renderH;
  g_hizMipCount = 0;
  if (kHiZPathValidated) {
    int hizW = std::max(1, renderW / 2);
    int hizH = std::max(1, renderH / 2);
    g_hizMipCount =
        (uint32_t)floor(log2(std::max(hizW, hizH))) + 1;
    g_hizMipCount = std::min(g_hizMipCount, (uint32_t)12);
    MTLTextureDescriptor *hizDesc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float
                                     width:hizW
                                    height:hizH
                                 mipmapped:YES];
    hizDesc.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
    hizDesc.storageMode = MTLStorageModePrivate;
    hizDesc.mipmapLevelCount = g_hizMipCount;
    g_hizPyramid = [g_device newTextureWithDescriptor:hizDesc];
  } else {
    dbg("Hi-Z disabled: multi-mip build/occlusion path is not yet validated\n");
  }

  if (g_oitAccumTex) {
    [g_oitAccumTex release];
    g_oitAccumTex = nil;
  }
  if (g_oitRevealTex) {
    [g_oitRevealTex release];
    g_oitRevealTex = nil;
  }
  MTLTextureDescriptor *oitAccumDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float
                                   width:renderW
                                  height:renderH
                               mipmapped:NO];
  oitAccumDesc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
  // OIT is accumulated in one render pass and sampled in another, so these
  // resources must survive the pass boundary.
  oitAccumDesc.storageMode = MTLStorageModePrivate;
  g_oitAccumTex = [g_device newTextureWithDescriptor:oitAccumDesc];
  MTLTextureDescriptor *oitRevDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatR8Unorm
                                   width:renderW
                                  height:renderH
                               mipmapped:NO];
  oitRevDesc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
  oitRevDesc.storageMode = MTLStorageModePrivate;
  g_oitRevealTex = [g_device newTextureWithDescriptor:oitRevDesc];
  dbg("OIT private render targets: %dx%d (accum RGBA16F + revealage R8)\n",
      renderW, renderH);
  g_depthReadBytesPerRow =
      ((NSUInteger)renderW * sizeof(float) + 255u) & ~255u;
  g_depthReadWidth = renderW;
  g_depthReadHeight = renderH;
  NSUInteger depthBufSize =
      g_depthReadBytesPerRow * (NSUInteger)renderH;
  for (int s = 0; s < kTripleBufferCount; s++) {
    g_tbDepthReadBuffer[s] =
        [g_device newBufferWithLength:depthBufSize
                              options:MTLStorageModeShared];
    g_tbDepthReadBuffer[s].label =
        [NSString stringWithFormat:@"MetalRender depth readback %d", s];
  }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsAvailable(
    JNIEnv *, jclass) {
  ensure_device();
  return (g_available && g_device) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nInit(
    JNIEnv *, jclass, jint width, jint height, jfloat scale) {
  ensure_system_power_notifications();
  ensure_device();
  g_rtWidth = (int)width;
  g_rtHeight = (int)height;
  g_scale = std::max(0.2f, std::min(1.0f, (float)scale));
  g_shuttingDown.store(false, std::memory_order_release);
  ensure_offscreen();
  load_shaders();
  set_iris_msl_compiler_ready(g_device != nil);
  return (g_device != nil) ? (jlong)0x1 : (jlong)0;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetSystemSleepCount(
    JNIEnv *, jclass) {
  return (jlong)g_systemSleepCount.load(std::memory_order_acquire);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetSystemWakeCount(
    JNIEnv *, jclass) {
  return (jlong)g_systemWakeCount.load(std::memory_order_acquire);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nResize(
    JNIEnv *, jclass, jlong handle, jint width, jint height, jfloat scale) {
  (void)handle;
  g_rtWidth = (int)width;
  g_rtHeight = (int)height;
  g_scale = std::max(0.2f, std::min(1.0f, (float)scale));
  ensure_offscreen();
}
static bool g_reuseTerrainFrame = false;
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetReuseTerrainFrame(
    JNIEnv *, jclass, jboolean reuse) {
  g_reuseTerrainFrame = (bool)reuse;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBeginFrame(
    JNIEnv *, jclass, jlong handle, jfloatArray proj, jfloatArray view,
    jfloat fogStart, jfloat fogEnd) {
  (void)handle;
  (void)proj;
  (void)view;
  (void)fogStart;
  (void)fogEnd;
  ensure_device();
  ensure_offscreen();
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nOnWorldLoaded(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  g_shuttingDown.store(false, std::memory_order_release);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nOnWorldUnloaded(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  g_shuttingDown.store(true, std::memory_order_release);
  g_surfaceSlotChanged.notify_all();
  drain_surface_slots(true);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDestroy(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  set_iris_msl_compiler_ready(false);
  g_shuttingDown.store(true, std::memory_order_release);
  g_surfaceSlotChanged.notify_all();
  drain_surface_slots(true);
}

static jint iris_msl_expected_function_type(jint stageOrdinal,
                                            MTLFunctionType *expectedType) {
  if (!expectedType)
    return kIrisMslCompileFailed;
  switch (stageOrdinal) {
  case 0: // VERTEX
    *expectedType = MTLFunctionTypeVertex;
    return kIrisMslCompileCompiled;
  case 1: // TESS_CONTROL is emitted as a Metal kernel.
    *expectedType = MTLFunctionTypeKernel;
    return kIrisMslCompileCompiled;
  case 2: // TESS_EVALUATION is emitted as a Metal vertex function.
    *expectedType = MTLFunctionTypeVertex;
    return kIrisMslCompileCompiled;
  case 3: // Metal has no geometry shader stage.
    return kIrisMslCompileUnsupported;
  case 4: // FRAGMENT
    *expectedType = MTLFunctionTypeFragment;
    return kIrisMslCompileCompiled;
  case 5: // COMPUTE
    *expectedType = MTLFunctionTypeKernel;
    return kIrisMslCompileCompiled;
  default:
    return kIrisMslCompileFailed;
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nValidateIrisMslLibrary(
    JNIEnv *env, jclass, jbyteArray mslUtf8, jint stageOrdinal) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_irisMslCompileMutex);
    if (!g_irisMslCompilerReady.load(std::memory_order_acquire) ||
        !g_device) {
      // A lifecycle race is DEFERRED work, not a terminal capability result,
      // so it has a distinct code and deliberately leaves every
      // process-lifetime counter unchanged.
      return kIrisMslCompileDeferred;
    }

    g_irisMslCompileAttemptCount.fetch_add(1, std::memory_order_relaxed);
    if (!mslUtf8) {
      return complete_iris_msl_validation(kIrisMslCompileFailed,
                                          "NULL_SOURCE", nil, "input");
    }

    jsize sourceLength = env->GetArrayLength(mslUtf8);
    if (sourceLength <= 0 || sourceLength > kIrisMslMaximumSourceBytes) {
      return complete_iris_msl_validation(kIrisMslCompileFailed,
                                          "SOURCE_SIZE", nil, "input");
    }

    MTLFunctionType expectedType = MTLFunctionTypeVisible;
    jint stageSupport =
        iris_msl_expected_function_type(stageOrdinal, &expectedType);
    if (stageSupport != kIrisMslCompileCompiled) {
      return complete_iris_msl_validation(
          stageSupport,
          stageSupport == kIrisMslCompileUnsupported ? "UNSUPPORTED_STAGE"
                                                     : "INVALID_STAGE",
          nil, "input");
    }
    if ([g_device argumentBuffersSupport] < MTLArgumentBuffersTier2) {
      return complete_iris_msl_validation(kIrisMslCompileUnsupported,
                                          "ARGUMENT_BUFFERS_TIER", nil,
                                          "metal");
    }

    std::vector<jbyte> sourceBytes;
    try {
      sourceBytes.resize((size_t)sourceLength);
    } catch (...) {
      return complete_iris_msl_validation(kIrisMslCompileFailed,
                                          "RESOURCE_LIMIT", nil, "native");
    }
    env->GetByteArrayRegion(mslUtf8, 0, sourceLength, sourceBytes.data());
    if (env->ExceptionCheck()) {
      return complete_iris_msl_validation(kIrisMslCompileFailed,
                                          "JNI_COPY", nil, "jni");
    }

    NSString *source = nil;
    MTLCompileOptions *options = nil;
    id<MTLLibrary> library = nil;
    id<MTLFunction> function = nil;
    NSError *compileError = nil;
    jint result = kIrisMslCompileFailed;
    const char *reason = "UNKNOWN";
    const char *fallbackDomain = "metal";
    @try {
      source = [[NSString alloc] initWithBytes:sourceBytes.data()
                                       length:(NSUInteger)sourceLength
                                     encoding:NSUTF8StringEncoding];
      if (!source) {
        reason = "INVALID_UTF8";
        fallbackDomain = "input";
      } else {
        options = [[MTLCompileOptions alloc] init];
        if (!options) {
          reason = "RESOURCE_LIMIT";
          fallbackDomain = "native";
        } else {
          options.languageVersion = MTLLanguageVersion3_0;
          options.libraryType = MTLLibraryTypeExecutable;
          options.preserveInvariance = YES;
          if (@available(macOS 15.0, *)) {
            options.mathMode = MTLMathModeSafe;
          } else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
            options.fastMathEnabled = NO;
#pragma clang diagnostic pop
          }

          library = [g_device newLibraryWithSource:source
                                            options:options
                                              error:&compileError];
          if (!library) {
            reason = "METAL_COMPILE";
          } else {
            g_irisMslLiveLibraryCount.fetch_add(1,
                                                std::memory_order_relaxed);
            function = [library newFunctionWithName:@"main0"];
            if (!function) {
              reason = "MISSING_MAIN0";
            } else if ([function functionType] != expectedType) {
              reason = "FUNCTION_TYPE";
            } else {
              result = kIrisMslCompileCompiled;
              reason = "COMPILED";
            }
          }
        }
      }
    } @catch (NSException *exception) {
      (void)exception;
      result = kIrisMslCompileFailed;
      reason = "OBJC_EXCEPTION";
      fallbackDomain = "NSException";
      compileError = nil;
    } @finally {
      if (function)
        [function release];
      if (library) {
        [library release];
        g_irisMslLiveLibraryCount.fetch_sub(1,
                                            std::memory_order_relaxed);
      }
      if (options)
        [options release];
      if (source)
        [source release];
    }
    return complete_iris_msl_validation(result, reason, compileError,
                                        fallbackDomain);
  }
}

namespace {
constexpr jint kIrisMetal4PipelineFailed = -1;
constexpr jint kIrisMetal4PipelineUnsupported = 0;
constexpr jint kIrisMetal4PipelineCompiled = 1;
constexpr jint kIrisMetal4PipelineCacheHit = 2;
constexpr jint kIrisMetal4PipelineDeferred = 3;
constexpr uint32_t kIrisMetal4DescriptorMagic = 0x4d525036;
constexpr uint32_t kIrisMetal4DescriptorSchema = 1;
constexpr jsize kIrisMetal4MaximumDescriptorBytes = 1024 * 1024;

class IrisPipelineByteReader {
public:
  IrisPipelineByteReader(const uint8_t *bytes, size_t size)
      : bytes_(bytes), size_(size) {}

  bool u8(uint8_t &value) {
    if (!ok_ || offset_ >= size_)
      return fail();
    value = bytes_[offset_++];
    return true;
  }

  bool boolean(bool &value) {
    uint8_t encoded = 0;
    if (!u8(encoded) || encoded > 1)
      return fail();
    value = encoded != 0;
    return true;
  }

  bool u32(uint32_t &value) {
    if (!ok_ || size_ - offset_ < 4)
      return fail();
    value = ((uint32_t)bytes_[offset_] << 24) |
            ((uint32_t)bytes_[offset_ + 1] << 16) |
            ((uint32_t)bytes_[offset_ + 2] << 8) |
            (uint32_t)bytes_[offset_ + 3];
    offset_ += 4;
    return true;
  }

  bool u64(uint64_t &value) {
    uint32_t high = 0;
    uint32_t low = 0;
    if (!u32(high) || !u32(low))
      return false;
    value = ((uint64_t)high << 32) | low;
    return true;
  }

  bool bytes(size_t length, std::vector<uint8_t> &value) {
    if (!ok_ || length > size_ - offset_)
      return fail();
    try {
      value.assign(bytes_ + offset_, bytes_ + offset_ + length);
    } catch (...) {
      return fail();
    }
    offset_ += length;
    return true;
  }

  bool bytesView(size_t length, const uint8_t *&value) {
    if (!ok_ || length > size_ - offset_)
      return fail();
    value = bytes_ + offset_;
    offset_ += length;
    return true;
  }

  bool string(std::string &value) {
    uint32_t length = 0;
    if (!u32(length) || length == 0 || length > 128 ||
        length > size_ - offset_)
      return fail();
    value.assign((const char *)bytes_ + offset_, (size_t)length);
    offset_ += length;
    for (char character : value) {
      bool safe = (character >= 'a' && character <= 'z') ||
                  (character >= '0' && character <= '9') ||
                  character == '.' || character == '_' || character == '-';
      if (!safe)
        return fail();
    }
    return true;
  }

  bool done() const { return ok_ && offset_ == size_; }
  size_t remaining() const { return ok_ ? size_ - offset_ : 0; }

private:
  bool fail() {
    ok_ = false;
    return false;
  }
  const uint8_t *bytes_;
  size_t size_;
  size_t offset_ = 0;
  bool ok_ = true;
};

struct IrisPipelineVertexBuffer {
  uint32_t index = 0;
  uint32_t stride = 0;
  uint32_t stepFunction = 0;
  uint32_t stepRate = 0;
};
struct IrisPipelineVertexAttribute {
  uint32_t location = 0;
  uint32_t buffer = 0;
  uint32_t offset = 0;
  std::string format;
};
struct IrisPipelineBlendEquation {
  uint32_t operation = 0;
  uint32_t source = 0;
  uint32_t destination = 0;
};
struct IrisPipelineColorAttachment {
  uint32_t slot = 0;
  std::string format;
  uint32_t writeMask = 0;
  bool blendEnabled = false;
  IrisPipelineBlendEquation rgb;
  IrisPipelineBlendEquation alpha;
};
struct IrisPipelineStencilFace {
  uint32_t compare = 0;
  uint32_t stencilFail = 0;
  uint32_t depthFail = 0;
  uint32_t pass = 0;
  uint32_t readMask = 0;
  uint32_t writeMask = 0;
  uint32_t reference = 0;
};
struct IrisPipelineFunctionConstant {
  uint32_t stage = 0;
  uint32_t index = 0;
  uint32_t type = 0;
  uint64_t bits = 0;
};
struct IrisParsedPipelineDescriptor {
  uint32_t passKind = 0;
  std::vector<IrisPipelineVertexBuffer> buffers;
  std::vector<IrisPipelineVertexAttribute> attributes;
  std::vector<IrisPipelineColorAttachment> colors;
  bool hasDepthFormat = false;
  std::string depthFormat;
  bool hasStencilFormat = false;
  std::string stencilFormat;
  uint32_t rasterSampleCount = 1;
  uint64_t sampleMask = UINT64_MAX;
  bool sampleCoverageEnabled = false;
  uint32_t sampleCoverageBits = 0;
  bool sampleCoverageInvert = false;
  bool alphaToCoverage = false;
  bool alphaToOne = false;
  bool depthTest = false;
  uint32_t depthCompare = 7;
  bool depthWrite = false;
  bool stencilEnabled = false;
  IrisPipelineStencilFace stencilFront;
  IrisPipelineStencilFace stencilBack;
  bool rasterizationEnabled = true;
  uint32_t cullMode = 0;
  uint32_t frontFace = 1;
  uint32_t frontFill = 0;
  uint32_t backFill = 0;
  uint32_t depthClip = 0;
  uint32_t polygonOffsetMask = 0;
  uint32_t depthBiasBits = 0;
  uint32_t slopeScaleBits = 0;
  uint32_t depthBiasClampBits = 0;
  uint32_t topology = 8;
  uint32_t restartMode = 0;
  uint32_t patchControlPoints = 0;
  std::vector<IrisPipelineFunctionConstant> constants;
};

static bool read_bounded_count(IrisPipelineByteReader &reader,
                               uint32_t maximum, uint32_t &count) {
  return reader.u32(count) && count <= maximum;
}

static bool read_blend_equation(IrisPipelineByteReader &reader,
                                IrisPipelineBlendEquation &equation) {
  return reader.u32(equation.operation) && equation.operation <= 4 &&
         reader.u32(equation.source) && equation.source <= 18 &&
         reader.u32(equation.destination) && equation.destination <= 18;
}

static bool read_stencil_face(IrisPipelineByteReader &reader,
                              IrisPipelineStencilFace &face) {
  return reader.u32(face.compare) && face.compare <= 7 &&
         reader.u32(face.stencilFail) && face.stencilFail <= 7 &&
         reader.u32(face.depthFail) && face.depthFail <= 7 &&
         reader.u32(face.pass) && face.pass <= 7 &&
         reader.u32(face.readMask) && reader.u32(face.writeMask) &&
         reader.u32(face.reference);
}

static bool parse_iris_metal4_pipeline_descriptor(
    const std::vector<jbyte> &bytes, IrisParsedPipelineDescriptor &result) {
  IrisPipelineByteReader reader((const uint8_t *)bytes.data(), bytes.size());
  uint32_t magic = 0;
  uint32_t schema = 0;
  if (!reader.u32(magic) || magic != kIrisMetal4DescriptorMagic ||
      !reader.u32(schema) || schema != kIrisMetal4DescriptorSchema ||
      !reader.u32(result.passKind) || result.passKind > 2)
    return false;

  uint32_t count = 0;
  if (!read_bounded_count(reader, 31, count))
    return false;
  result.buffers.resize(count);
  for (auto &buffer : result.buffers) {
    if (!reader.u32(buffer.index) || buffer.index >= 31 ||
        !reader.u32(buffer.stride) || buffer.stride == 0 ||
        buffer.stride > 65536 || !reader.u32(buffer.stepFunction) ||
        buffer.stepFunction > 2 || !reader.u32(buffer.stepRate) ||
        (buffer.stepFunction == 0 && buffer.stepRate != 0) ||
        (buffer.stepFunction == 1 && buffer.stepRate == 0) ||
        (buffer.stepFunction == 2 && buffer.stepRate != 0))
      return false;
  }
  if (!read_bounded_count(reader, 31, count))
    return false;
  result.attributes.resize(count);
  for (auto &attribute : result.attributes) {
    if (!reader.u32(attribute.location) || attribute.location >= 31 ||
        !reader.u32(attribute.buffer) || attribute.buffer >= 31 ||
        !reader.u32(attribute.offset) ||
        !reader.string(attribute.format))
      return false;
  }
  if (!read_bounded_count(reader, 8, count))
    return false;
  result.colors.resize(count);
  for (auto &color : result.colors) {
    if (!reader.u32(color.slot) || color.slot >= 8 ||
        !reader.string(color.format) || !reader.u32(color.writeMask) ||
        (color.writeMask & ~0xfu) != 0 ||
        !reader.boolean(color.blendEnabled) ||
        !read_blend_equation(reader, color.rgb) ||
        !read_blend_equation(reader, color.alpha))
      return false;
  }
  if (!reader.boolean(result.hasDepthFormat) ||
      (result.hasDepthFormat && !reader.string(result.depthFormat)) ||
      !reader.boolean(result.hasStencilFormat) ||
      (result.hasStencilFormat && !reader.string(result.stencilFormat)) ||
      !reader.u32(result.rasterSampleCount) ||
      result.rasterSampleCount == 0 || result.rasterSampleCount > 64 ||
      !reader.u64(result.sampleMask) ||
      !reader.boolean(result.sampleCoverageEnabled) ||
      !reader.u32(result.sampleCoverageBits) ||
      !reader.boolean(result.sampleCoverageInvert) ||
      !reader.boolean(result.alphaToCoverage) ||
      !reader.boolean(result.alphaToOne) ||
      !reader.boolean(result.depthTest) ||
      !reader.u32(result.depthCompare) || result.depthCompare > 7 ||
      !reader.boolean(result.depthWrite) ||
      !reader.boolean(result.stencilEnabled) ||
      !read_stencil_face(reader, result.stencilFront) ||
      !read_stencil_face(reader, result.stencilBack) ||
      !reader.boolean(result.rasterizationEnabled) ||
      !reader.u32(result.cullMode) || result.cullMode > 3 ||
      !reader.u32(result.frontFace) || result.frontFace > 1 ||
      !reader.u32(result.frontFill) || result.frontFill > 2 ||
      !reader.u32(result.backFill) || result.backFill > 2 ||
      !reader.u32(result.depthClip) || result.depthClip > 1 ||
      !reader.u32(result.polygonOffsetMask) ||
      (result.polygonOffsetMask & ~0x7u) != 0 ||
      !reader.u32(result.depthBiasBits) ||
      !reader.u32(result.slopeScaleBits) ||
      !reader.u32(result.depthBiasClampBits) ||
      !reader.u32(result.topology) || result.topology > 8 ||
      !reader.u32(result.restartMode) || result.restartMode > 3 ||
      !reader.u32(result.patchControlPoints))
    return false;

  if (!read_bounded_count(reader, 256, count))
    return false;
  result.constants.resize(count);
  for (auto &constant : result.constants) {
    if (!reader.u32(constant.stage) || constant.stage > 5 ||
        !reader.u32(constant.index) || !reader.u32(constant.type) ||
        constant.type > 6 || !reader.u64(constant.bits))
      return false;
  }
  if (!reader.done())
    return false;
  if (result.passKind == 2) {
    return result.buffers.empty() && result.attributes.empty() &&
           result.colors.empty() && !result.hasDepthFormat &&
           !result.hasStencilFormat && result.topology == 8;
  }
  return result.topology != 8;
}

constexpr uint32_t kIrisShadowReplayMagic = 0x4d525837;
constexpr uint32_t kIrisShadowReplaySchema = 8;
constexpr jsize kIrisShadowReplayMaximumPacketBytes = 384 * 1024 * 1024;
constexpr uint32_t kIrisShadowReplayMaximumExtent = 4096;
constexpr uint32_t kIrisShadowReplayMaximumBuffers = 2048;
constexpr uint64_t kIrisShadowReplayMaximumBufferBytes = 128ULL * 1024 * 1024;
constexpr uint32_t kIrisShadowReplayMaximumTextures = 256;
constexpr uint64_t kIrisShadowReplayMaximumTextureBytes = 256ULL * 1024 * 1024;
constexpr uint32_t kIrisShadowReplayMaximumArguments = 8192;
constexpr uint64_t kIrisShadowReplayMaximumInlineBytes = 1ULL * 1024 * 1024;

struct IrisShadowRect {
  int32_t x = 0;
  int32_t y = 0;
  int32_t width = 0;
  int32_t height = 0;
};

struct IrisShadowDraw {
  uint32_t kind = 0;
  uint32_t primitiveMode = 0;
  int32_t firstVertex = 0;
  uint32_t vertexCount = 0;
  uint32_t instanceCount = 0;
  uint32_t baseInstance = 0;
  uint32_t indexElementBytes = 0;
  uint32_t groupsX = 0;
  uint32_t groupsY = 0;
  uint32_t groupsZ = 0;
  uint32_t localSizeX = 0;
  uint32_t localSizeY = 0;
  uint32_t localSizeZ = 0;
  struct Indexed {
    uint64_t offset = 0;
    uint32_t count = 0;
    int32_t baseVertex = 0;
  };
  std::vector<Indexed> indexed;
};

struct IrisShadowTexture {
  uint32_t glName = 0;
  std::string format;
  uint32_t width = 0;
  uint32_t height = 0;
  uint32_t layer = 0;
  uint32_t mipLevel = 0;
  uint32_t bytesPerPixel = 0;
  uint32_t storageKind = 0;
  uint64_t sharedHandle = 0;
  std::vector<uint8_t> bytes;
};

struct IrisShadowBuffer {
  uint32_t storageKind = 0;
  uint32_t byteLength = 0;
  uint64_t sharedHandle = 0;
  std::vector<uint8_t> bytes;
};

struct IrisShadowSampler {
  uint32_t minFilter = 0;
  uint32_t magFilter = 0;
  uint32_t wrapS = 0;
  uint32_t wrapT = 0;
  uint32_t wrapR = 0;
  uint32_t compareMode = 0;
  uint32_t compareFunc = 0;
  uint32_t baseLevel = 0;
  uint32_t maxLevel = 0;
  uint32_t minLodBits = 0;
  uint32_t maxLodBits = 0;
  uint32_t lodBiasBits = 0;
  uint32_t maxAnisotropyBits = 0;
  bool integerBorderColor = false;
  uint32_t borderColor[4] = {0, 0, 0, 0};
};

struct IrisShadowArgument {
  uint32_t argumentBufferIndex = 0;
  uint32_t id = 0;
  uint32_t kind = 0;
  uint32_t reference = 0;
  uint32_t auxiliary = 0;
  std::vector<uint8_t> inlineBytes;
  IrisShadowSampler sampler;
};

struct IrisShadowStageArguments {
  uint32_t stage = 0;
  std::vector<IrisShadowArgument> arguments;
};

struct IrisShadowReplayPacket {
  uint32_t width = 0;
  uint32_t height = 0;
  IrisShadowRect viewport;
  bool scissorEnabled = false;
  IrisShadowRect scissor;
  IrisShadowDraw draw;
  std::vector<IrisShadowBuffer> buffers;
  std::vector<std::pair<uint32_t, uint32_t>> vertexBuffers;
  int32_t indexBufferImage = -1;
  std::vector<IrisShadowTexture> textures;
  std::vector<IrisShadowStageArguments> stages;
};

static bool iris_shadow_read_i32(IrisPipelineByteReader &reader,
                                 int32_t &value) {
  uint32_t encoded = 0;
  if (!reader.u32(encoded))
    return false;
  value = (int32_t)encoded;
  return true;
}

static bool iris_shadow_read_rect(IrisPipelineByteReader &reader,
                                  IrisShadowRect &rect) {
  return iris_shadow_read_i32(reader, rect.x) &&
         iris_shadow_read_i32(reader, rect.y) &&
         iris_shadow_read_i32(reader, rect.width) &&
         iris_shadow_read_i32(reader, rect.height);
}

static bool iris_shadow_add_bounded(uint64_t &total, uint64_t value,
                                    uint64_t maximum) {
  if (value > maximum || total > maximum - value)
    return false;
  total += value;
  return true;
}

static bool iris_shadow_read_sampler(IrisPipelineByteReader &reader,
                                     IrisShadowSampler &sampler) {
  if (!reader.u32(sampler.minFilter) || !reader.u32(sampler.magFilter) ||
      !reader.u32(sampler.wrapS) || !reader.u32(sampler.wrapT) ||
      !reader.u32(sampler.wrapR) || !reader.u32(sampler.compareMode) ||
      !reader.u32(sampler.compareFunc) || !reader.u32(sampler.baseLevel) ||
      !reader.u32(sampler.maxLevel) ||
      !reader.u32(sampler.minLodBits) ||
      !reader.u32(sampler.maxLodBits) ||
      !reader.u32(sampler.lodBiasBits) ||
      !reader.u32(sampler.maxAnisotropyBits) ||
      !reader.boolean(sampler.integerBorderColor))
    return false;
  for (uint32_t &component : sampler.borderColor) {
    if (!reader.u32(component))
      return false;
  }
  return true;
}

static bool parse_iris_shadow_replay_packet_data(
    const uint8_t *bytes, size_t byteCount,
    IrisShadowReplayPacket &result, bool allowExternalTextures) {
  IrisPipelineByteReader reader(bytes, byteCount);
  uint32_t magic = 0;
  uint32_t schema = 0;
  if (!reader.u32(magic) || magic != kIrisShadowReplayMagic ||
      !reader.u32(schema) || schema != kIrisShadowReplaySchema ||
      !reader.u32(result.width) || result.width == 0 ||
      result.width > kIrisShadowReplayMaximumExtent ||
      !reader.u32(result.height) || result.height == 0 ||
      result.height > kIrisShadowReplayMaximumExtent ||
      !iris_shadow_read_rect(reader, result.viewport) ||
      result.viewport.width <= 0 || result.viewport.height <= 0 ||
      !reader.boolean(result.scissorEnabled) ||
      !iris_shadow_read_rect(reader, result.scissor) ||
      (result.scissorEnabled &&
       (result.scissor.width <= 0 || result.scissor.height <= 0)))
    return false;

  if (!reader.u32(result.draw.kind) || result.draw.kind < 1 ||
      result.draw.kind > 4)
    return false;
  if (result.draw.kind == 4) {
    if (!reader.u32(result.draw.groupsX) ||
        !reader.u32(result.draw.groupsY) ||
        !reader.u32(result.draw.groupsZ) ||
        !reader.u32(result.draw.localSizeX) ||
        !reader.u32(result.draw.localSizeY) ||
        !reader.u32(result.draw.localSizeZ) ||
        result.draw.groupsX == 0 || result.draw.groupsY == 0 ||
        result.draw.groupsZ == 0 ||
        result.draw.localSizeX == 0 || result.draw.localSizeY == 0 ||
        result.draw.localSizeZ == 0 ||
        (uint64_t)result.draw.localSizeX *
            result.draw.localSizeY * result.draw.localSizeZ > 1024ULL ||
        result.draw.groupsX > 1048576 || result.draw.groupsY > 1048576 ||
        result.draw.groupsZ > 1048576)
      return false;
  } else if (!reader.u32(result.draw.primitiveMode)) {
    return false;
  }
  if (result.draw.kind == 1) {
    if (!iris_shadow_read_i32(reader, result.draw.firstVertex) ||
        result.draw.firstVertex < 0 || !reader.u32(result.draw.vertexCount) ||
        result.draw.vertexCount == 0 ||
        !reader.u32(result.draw.instanceCount) ||
        result.draw.instanceCount == 0 ||
        !reader.u32(result.draw.baseInstance))
      return false;
  } else if (result.draw.kind == 2) {
    IrisShadowDraw::Indexed indexed;
    if (!reader.u64(indexed.offset) || !reader.u32(indexed.count) ||
        indexed.count == 0 || !reader.u32(result.draw.indexElementBytes) ||
        (result.draw.indexElementBytes != 2 &&
         result.draw.indexElementBytes != 4) ||
        !iris_shadow_read_i32(reader, indexed.baseVertex) ||
        !reader.u32(result.draw.instanceCount) ||
        result.draw.instanceCount == 0 ||
        !reader.u32(result.draw.baseInstance))
      return false;
    result.draw.indexed.push_back(indexed);
  } else {
    uint32_t count = 0;
    if (!reader.u32(result.draw.indexElementBytes) ||
        (result.draw.indexElementBytes != 2 &&
         result.draw.indexElementBytes != 4) ||
        !read_bounded_count(reader, 65536, count) || count == 0)
      return false;
    try {
      result.draw.indexed.resize(count);
    } catch (...) {
      return false;
    }
    for (auto &indexed : result.draw.indexed) {
      if (!reader.u64(indexed.offset) || !reader.u32(indexed.count) ||
          indexed.count == 0 ||
          !iris_shadow_read_i32(reader, indexed.baseVertex))
        return false;
    }
    result.draw.instanceCount = 1;
  }

  uint32_t count = 0;
  uint64_t totalBufferBytes = 0;
  if (!read_bounded_count(reader, kIrisShadowReplayMaximumBuffers, count))
    return false;
  try {
    result.buffers.resize(count);
  } catch (...) {
    return false;
  }
  for (auto &buffer : result.buffers) {
    if (!reader.u32(buffer.storageKind) || buffer.storageKind < 1 ||
        buffer.storageKind > (allowExternalTextures ? 3u : 2u) ||
        !reader.u32(buffer.byteLength) ||
        buffer.byteLength == 0 ||
        !iris_shadow_add_bounded(totalBufferBytes, buffer.byteLength,
                                 kIrisShadowReplayMaximumBufferBytes)) {
      return false;
    }
    if (buffer.storageKind == 1) {
      if (!reader.bytes(buffer.byteLength, buffer.bytes))
        return false;
    } else if (!reader.u64(buffer.sharedHandle) ||
               buffer.sharedHandle == 0) {
      return false;
    }
  }

  if (!read_bounded_count(reader, 31, count))
    return false;
  try {
    result.vertexBuffers.resize(count);
  } catch (...) {
    return false;
  }
  bool occupiedVertexSlots[31] = {};
  for (auto &binding : result.vertexBuffers) {
    if (!reader.u32(binding.first) || binding.first >= 31 ||
        occupiedVertexSlots[binding.first] || !reader.u32(binding.second) ||
        binding.second >= result.buffers.size())
      return false;
    occupiedVertexSlots[binding.first] = true;
  }
  if (!iris_shadow_read_i32(reader, result.indexBufferImage) ||
      result.indexBufferImage < -1 ||
      (result.indexBufferImage >= 0 &&
       (size_t)result.indexBufferImage >= result.buffers.size()) ||
      ((result.draw.kind == 2 || result.draw.kind == 3) &&
       result.indexBufferImage < 0))
    return false;

  uint64_t totalTextureBytes = 0;
  if (!read_bounded_count(reader, kIrisShadowReplayMaximumTextures, count))
    return false;
  try {
    result.textures.resize(count);
  } catch (...) {
    return false;
  }
  std::unordered_map<uint32_t, bool> textureNames;
  for (auto &texture : result.textures) {
    if (!reader.u32(texture.glName) || texture.glName == 0 ||
        !textureNames.emplace(texture.glName, true).second ||
        !reader.string(texture.format) || !reader.u32(texture.width) ||
        texture.width == 0 || texture.width > 16384 ||
        !reader.u32(texture.height) || texture.height == 0 ||
        texture.height > 16384 || !reader.u32(texture.layer) ||
        !reader.u32(texture.mipLevel) ||
        !reader.u32(texture.bytesPerPixel) ||
        texture.bytesPerPixel == 0 || texture.bytesPerPixel > 16 ||
        !reader.u32(texture.storageKind) || texture.storageKind < 1 ||
        texture.storageKind > (allowExternalTextures ? 4u : 2u))
      return false;
    uint64_t expected = (uint64_t)texture.width * texture.height;
    if (expected > UINT64_MAX / texture.bytesPerPixel)
      return false;
    expected *= texture.bytesPerPixel;
    if (texture.storageKind == 1) {
      uint32_t length = 0;
      if (!reader.u32(length) || expected != length ||
          !iris_shadow_add_bounded(totalTextureBytes, length,
                                   kIrisShadowReplayMaximumTextureBytes) ||
          !reader.bytes(length, texture.bytes)) {
        return false;
      }
    } else if (texture.storageKind == 2) {
      if (!reader.u64(texture.sharedHandle) ||
          texture.sharedHandle == 0 || texture.layer != 0 ||
          texture.mipLevel != 0 ||
          !iris_shadow_add_bounded(totalTextureBytes, expected,
                                   kIrisShadowReplayMaximumTextureBytes)) {
        return false;
      }
    } else if (texture.storageKind == 4) {
      if (!reader.u64(texture.sharedHandle) ||
          texture.sharedHandle == 0 ||
          !iris_shadow_add_bounded(totalTextureBytes, expected,
                                   kIrisShadowReplayMaximumTextureBytes)) {
        return false;
      }
    } else if (!iris_shadow_add_bounded(
                   totalTextureBytes, expected,
                   kIrisShadowReplayMaximumTextureBytes)) {
      return false;
    }
  }

  if (!read_bounded_count(reader, 2, count))
    return false;
  try {
    result.stages.resize(count);
  } catch (...) {
    return false;
  }
  bool occupiedStages[6] = {};
  uint32_t totalArguments = 0;
  uint64_t totalInlineBytes = 0;
  for (auto &stage : result.stages) {
    uint32_t argumentCount = 0;
    if (!reader.u32(stage.stage) ||
        (stage.stage != 0 && stage.stage != 4 && stage.stage != 5) ||
        occupiedStages[stage.stage] ||
        !read_bounded_count(reader, kIrisShadowReplayMaximumArguments,
                            argumentCount) ||
        totalArguments > kIrisShadowReplayMaximumArguments - argumentCount)
      return false;
    occupiedStages[stage.stage] = true;
    totalArguments += argumentCount;
    try {
      stage.arguments.resize(argumentCount);
    } catch (...) {
      return false;
    }
    std::unordered_map<uint64_t, bool> occupiedIds;
    for (auto &argument : stage.arguments) {
      if (!reader.u32(argument.argumentBufferIndex) ||
          argument.argumentBufferIndex >= 31 || !reader.u32(argument.id) ||
          argument.id > 65535 ||
          !occupiedIds.emplace(
              ((uint64_t)argument.argumentBufferIndex << 32) | argument.id,
              true).second ||
          !reader.u32(argument.kind) || argument.kind < 1 ||
          argument.kind > 7)
        return false;
      if (argument.kind == 1) {
        uint32_t length = 0;
        if (!reader.u32(length) || length == 0 ||
            !iris_shadow_add_bounded(totalInlineBytes, length,
                                     kIrisShadowReplayMaximumInlineBytes) ||
            !reader.bytes(length, argument.inlineBytes))
          return false;
      } else if (argument.kind == 2 || argument.kind == 3) {
        if (!reader.u32(argument.reference) ||
            (argument.kind == 2 &&
             argument.reference >= result.buffers.size()) ||
            (argument.kind == 3 &&
             textureNames.find(argument.reference) == textureNames.end()))
          return false;
      } else if (argument.kind == 7) {
        if (!reader.u32(argument.reference) ||
            argument.reference >= result.buffers.size() ||
            !reader.u32(argument.auxiliary) || argument.auxiliary == 0)
          return false;
      } else if (argument.kind == 5 &&
                 !iris_shadow_read_sampler(reader, argument.sampler)) {
        return false;
      }
    }
  }
  return reader.done();
}

static bool parse_iris_shadow_replay_packet(
    const std::vector<jbyte> &bytes, IrisShadowReplayPacket &result) {
  return parse_iris_shadow_replay_packet_data(
      (const uint8_t *)bytes.data(), bytes.size(), result, false);
}

static bool parse_iris_shadow_replay_packet(
    const std::vector<uint8_t> &bytes, IrisShadowReplayPacket &result) {
  return parse_iris_shadow_replay_packet_data(bytes.data(), bytes.size(),
                                               result, false);
}

static bool parse_iris_shadow_graph_replay_packet(
    const std::vector<uint8_t> &bytes, IrisShadowReplayPacket &result) {
  return parse_iris_shadow_replay_packet_data(bytes.data(), bytes.size(),
                                               result, true);
}

static bool parse_iris_shadow_graph_replay_packet(
    const uint8_t *bytes, size_t byteCount,
    IrisShadowReplayPacket &result) {
  return parse_iris_shadow_replay_packet_data(bytes, byteCount, result,
                                               true);
}

static MTLPixelFormat iris_metal_pixel_format(const std::string &name) {
#define IRIS_PIXEL(n, value)                                                   \
  if (name == n)                                                              \
    return value
  IRIS_PIXEL("r8-unorm", MTLPixelFormatR8Unorm);
  IRIS_PIXEL("r8-snorm", MTLPixelFormatR8Snorm);
  IRIS_PIXEL("rg8-unorm", MTLPixelFormatRG8Unorm);
  IRIS_PIXEL("rg8-snorm", MTLPixelFormatRG8Snorm);
  IRIS_PIXEL("rgb8-unorm", MTLPixelFormatRGBA8Unorm);
  IRIS_PIXEL("rgb8-snorm", MTLPixelFormatRGBA8Snorm);
  IRIS_PIXEL("rgba8-unorm", MTLPixelFormatRGBA8Unorm);
  IRIS_PIXEL("rgba8-snorm", MTLPixelFormatRGBA8Snorm);
  IRIS_PIXEL("r16-unorm", MTLPixelFormatR16Unorm);
  IRIS_PIXEL("r16-snorm", MTLPixelFormatR16Snorm);
  IRIS_PIXEL("rg16-unorm", MTLPixelFormatRG16Unorm);
  IRIS_PIXEL("rg16-snorm", MTLPixelFormatRG16Snorm);
  IRIS_PIXEL("rgb16-unorm", MTLPixelFormatRGBA16Unorm);
  IRIS_PIXEL("rgb16-snorm", MTLPixelFormatRGBA16Snorm);
  IRIS_PIXEL("rgba16-unorm", MTLPixelFormatRGBA16Unorm);
  IRIS_PIXEL("rgba16-snorm", MTLPixelFormatRGBA16Snorm);
  IRIS_PIXEL("r16-float", MTLPixelFormatR16Float);
  IRIS_PIXEL("rg16-float", MTLPixelFormatRG16Float);
  IRIS_PIXEL("rgb16-float", MTLPixelFormatRGBA16Float);
  IRIS_PIXEL("rgba16-float", MTLPixelFormatRGBA16Float);
  IRIS_PIXEL("r32-float", MTLPixelFormatR32Float);
  IRIS_PIXEL("rg32-float", MTLPixelFormatRG32Float);
  IRIS_PIXEL("rgb32-float", MTLPixelFormatRGBA32Float);
  IRIS_PIXEL("rgba32-float", MTLPixelFormatRGBA32Float);
  IRIS_PIXEL("r8-sint", MTLPixelFormatR8Sint);
  IRIS_PIXEL("r8-uint", MTLPixelFormatR8Uint);
  IRIS_PIXEL("rg8-sint", MTLPixelFormatRG8Sint);
  IRIS_PIXEL("rg8-uint", MTLPixelFormatRG8Uint);
  IRIS_PIXEL("rgb8-sint", MTLPixelFormatRGBA8Sint);
  IRIS_PIXEL("rgb8-uint", MTLPixelFormatRGBA8Uint);
  IRIS_PIXEL("rgba8-sint", MTLPixelFormatRGBA8Sint);
  IRIS_PIXEL("rgba8-uint", MTLPixelFormatRGBA8Uint);
  IRIS_PIXEL("r16-sint", MTLPixelFormatR16Sint);
  IRIS_PIXEL("r16-uint", MTLPixelFormatR16Uint);
  IRIS_PIXEL("rg16-sint", MTLPixelFormatRG16Sint);
  IRIS_PIXEL("rg16-uint", MTLPixelFormatRG16Uint);
  IRIS_PIXEL("rgb16-sint", MTLPixelFormatRGBA16Sint);
  IRIS_PIXEL("rgb16-uint", MTLPixelFormatRGBA16Uint);
  IRIS_PIXEL("rgba16-sint", MTLPixelFormatRGBA16Sint);
  IRIS_PIXEL("rgba16-uint", MTLPixelFormatRGBA16Uint);
  IRIS_PIXEL("r32-sint", MTLPixelFormatR32Sint);
  IRIS_PIXEL("r32-uint", MTLPixelFormatR32Uint);
  IRIS_PIXEL("rg32-sint", MTLPixelFormatRG32Sint);
  IRIS_PIXEL("rg32-uint", MTLPixelFormatRG32Uint);
  IRIS_PIXEL("rgb32-sint", MTLPixelFormatRGBA32Sint);
  IRIS_PIXEL("rgb32-uint", MTLPixelFormatRGBA32Uint);
  IRIS_PIXEL("rgba32-sint", MTLPixelFormatRGBA32Sint);
  IRIS_PIXEL("rgba32-uint", MTLPixelFormatRGBA32Uint);
  IRIS_PIXEL("rgb10a2-unorm", MTLPixelFormatRGB10A2Unorm);
  IRIS_PIXEL("rgb10a2-uint", MTLPixelFormatRGB10A2Uint);
  IRIS_PIXEL("rg11b10-float", MTLPixelFormatRG11B10Float);
  IRIS_PIXEL("rgb9e5-float", MTLPixelFormatRGB9E5Float);
  IRIS_PIXEL("d16-unorm", MTLPixelFormatDepth16Unorm);
  IRIS_PIXEL("d24-unorm-s8-uint", MTLPixelFormatDepth24Unorm_Stencil8);
  IRIS_PIXEL("d32-float", MTLPixelFormatDepth32Float);
  IRIS_PIXEL("d32-float-s8-uint", MTLPixelFormatDepth32Float_Stencil8);
  IRIS_PIXEL("s8-uint", MTLPixelFormatStencil8);
#undef IRIS_PIXEL
  return MTLPixelFormatInvalid;
}

static bool iris_metal4_graph_descriptor_matches(
    const IrisMetal4GraphTexture &entry, MTLPixelFormat format,
    uint32_t sampleCount, uint32_t width, uint32_t height,
    uint32_t depthOrLayers, uint32_t mipLevels, uint32_t usage) {
  return entry.texture && entry.pixelFormat == format &&
         entry.sampleCount == sampleCount && entry.width == width &&
         entry.height == height && entry.depthOrLayers == depthOrLayers &&
         entry.mipLevels == mipLevels && entry.usage == usage;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nEnsureIrisMetal4GraphTexture(
    JNIEnv *env, jclass, jlong contextGeneration, jint glTexture,
    jlong resourceGeneration, jstring formatValue, jint sampleCount,
    jint width, jint height, jint depthOrLayers, jint mipLevels,
    jint usage) {
  std::lock_guard<std::mutex> executionLock(g_irisMetal4ExecutionMutex);
  @autoreleasepool {
    constexpr uint32_t kShaderRead = 1u;
    constexpr uint32_t kShaderWrite = 1u << 1;
    constexpr uint32_t kRenderTarget = 1u << 2;
    constexpr uint32_t kTransferDestination = 1u << 3;
    constexpr uint32_t kKnownUsage = kShaderRead | kShaderWrite |
                                     kRenderTarget | kTransferDestination;
    if (!g_device || !g_metal4CommandQueue ||
        !g_metal4RuntimeVerified.load(std::memory_order_acquire) ||
        contextGeneration <= 0 || glTexture <= 0 ||
        resourceGeneration <= 0 || !formatValue ||
        sampleCount <= 0 || sampleCount > 16 || width <= 0 || height <= 0 ||
        width > 16384 || height > 16384 || depthOrLayers != 1 ||
        mipLevels <= 0 || mipLevels > 15 || usage <= 0 ||
        ((uint32_t)usage & ~kKnownUsage) != 0 ||
        ((uint32_t)usage & (kShaderWrite | kRenderTarget |
                            kTransferDestination)) == 0) {
      return 0;
    }
    uint32_t maximumMipLevels = 1;
    uint32_t maximumDimension = (uint32_t)std::max(width, height);
    while (maximumDimension > 1) {
      maximumDimension >>= 1;
      maximumMipLevels++;
    }
    if ((uint32_t)mipLevels > maximumMipLevels ||
        (sampleCount > 1 && mipLevels != 1) ||
        ![g_device supportsTextureSampleCount:(NSUInteger)sampleCount]) {
      return 0;
    }

    jsize formatLength = env->GetStringUTFLength(formatValue);
    if (formatLength <= 0 || formatLength > 128)
      return 0;
    const char *formatUtf8 = env->GetStringUTFChars(formatValue, nullptr);
    if (!formatUtf8)
      return 0;
    std::string formatName(formatUtf8, (size_t)formatLength);
    env->ReleaseStringUTFChars(formatValue, formatUtf8);
    MTLPixelFormat pixelFormat = iris_metal_pixel_format(formatName);
    if (pixelFormat == MTLPixelFormatInvalid)
      return 0;

    IrisMetal4GraphTextureKey key{
        (uint64_t)contextGeneration, (uint32_t)glTexture,
        (uint64_t)resourceGeneration};
    std::lock_guard<std::mutex> lock(g_irisMetal4GraphTextureMutex);
    auto existing = g_irisMetal4GraphTextures.find(key);
    if (existing != g_irisMetal4GraphTextures.end() &&
        iris_metal4_graph_descriptor_matches(existing->second, pixelFormat,
            (uint32_t)sampleCount, (uint32_t)width, (uint32_t)height,
            (uint32_t)depthOrLayers, (uint32_t)mipLevels,
            (uint32_t)usage)) {
      return (jlong)existing->second.token;
    }
    if (existing == g_irisMetal4GraphTextures.end() &&
        g_irisMetal4GraphTextures.size() >=
            kIrisMetal4GraphTextureLimit) {
      return 0;
    }

    @try {
      MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
          texture2DDescriptorWithPixelFormat:pixelFormat
                                       width:(NSUInteger)width
                                      height:(NSUInteger)height
                                   mipmapped:mipLevels > 1];
      descriptor.textureType = sampleCount > 1
          ? MTLTextureType2DMultisample : MTLTextureType2D;
      descriptor.sampleCount = (NSUInteger)sampleCount;
      descriptor.mipmapLevelCount = (NSUInteger)mipLevels;
      descriptor.storageMode = MTLStorageModePrivate;
      descriptor.hazardTrackingMode = MTLHazardTrackingModeTracked;
      MTLTextureUsage metalUsage = MTLTextureUsageUnknown;
      if (((uint32_t)usage & kShaderRead) != 0)
        metalUsage |= MTLTextureUsageShaderRead;
      if (((uint32_t)usage & kShaderWrite) != 0)
        metalUsage |= MTLTextureUsageShaderWrite;
      if (((uint32_t)usage & kRenderTarget) != 0)
        metalUsage |= MTLTextureUsageRenderTarget;
      descriptor.usage = metalUsage;

      MTLSizeAndAlign sizeAndAlign =
          [g_device heapTextureSizeAndAlignWithDescriptor:descriptor];
      uint64_t allocationBytes = (uint64_t)sizeAndAlign.size;
      uint64_t replacedBytes = existing == g_irisMetal4GraphTextures.end()
          ? 0 : existing->second.allocatedBytes;
      uint64_t retainedBytes =
          g_irisMetal4GraphTextureBytes >= replacedBytes
              ? g_irisMetal4GraphTextureBytes - replacedBytes
              : 0;
      if (allocationBytes == 0 ||
          allocationBytes > kIrisMetal4GraphTextureSingleByteLimit ||
          retainedBytes > kIrisMetal4GraphTextureByteLimit -
              allocationBytes) {
        return 0;
      }
      id<MTLTexture> texture =
          [g_device newTextureWithDescriptor:descriptor];
      if (!texture)
        return 0;

      IrisMetal4GraphTexture replacement;
      replacement.token = g_irisMetal4GraphTextureSequence.fetch_add(
          1, std::memory_order_relaxed);
      if (replacement.token == 0) {
        replacement.token = g_irisMetal4GraphTextureSequence.fetch_add(
            1, std::memory_order_relaxed);
      }
      replacement.pixelFormat = pixelFormat;
      replacement.sampleCount = (uint32_t)sampleCount;
      replacement.width = (uint32_t)width;
      replacement.height = (uint32_t)height;
      replacement.depthOrLayers = (uint32_t)depthOrLayers;
      replacement.mipLevels = (uint32_t)mipLevels;
      replacement.usage = (uint32_t)usage;
      replacement.allocatedBytes = allocationBytes;
      replacement.texture = texture;
      if (existing == g_irisMetal4GraphTextures.end()) {
        g_irisMetal4GraphTextures.emplace(key, replacement);
      } else {
        if (existing->second.texture)
          [existing->second.texture release];
        existing->second = replacement;
      }
      g_irisMetal4GraphTextureBytes = retainedBytes + allocationBytes;
      return (jlong)replacement.token;
    } @catch (NSException *exception) {
      dbg("WARN: Iris graph texture allocation raised %s: %s\n",
          exception.name.UTF8String ?: "NSException",
          exception.reason.UTF8String ?: "unknown reason");
      return 0;
    }
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMetal4GraphTextureCount(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(g_irisMetal4GraphTextureMutex);
  return (jint)g_irisMetal4GraphTextures.size();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMetal4GraphTextureBytes(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(g_irisMetal4GraphTextureMutex);
  return (jlong)g_irisMetal4GraphTextureBytes;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nResetIrisMetal4GraphResources(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> executionLock(g_irisMetal4ExecutionMutex);
  @autoreleasepool {
    reset_iris_metal4_graph_resources();
  }
}

constexpr uint32_t kIrisMetal4GraphFrameMagic = 0x4d474639;
constexpr uint32_t kIrisMetal4GraphFrameSchema = 5;
constexpr uint32_t kIrisMetal4GraphFrameLegacySchema = 4;
constexpr jsize kIrisMetal4GraphFrameMaximumPacketBytes =
    384 * 1024 * 1024;
constexpr jlong kIrisGraphReasonDrawPipelineUnavailable = 8;
constexpr jlong kIrisGraphReasonDrawPipelineStateMismatch = 9;
constexpr jlong kIrisGraphReasonDrawColorSlotMismatch = 10;
constexpr jlong kIrisGraphReasonDrawColorTargetMismatch = 11;
constexpr jlong kIrisGraphReasonDrawColorCoverageMismatch = 12;
constexpr jlong kIrisGraphReasonDrawDepthStencilPresenceMismatch = 13;
constexpr jlong kIrisGraphReasonDrawDepthTargetMismatch = 14;
constexpr jlong kIrisGraphReasonDrawStencilTargetMismatch = 15;
constexpr jlong kIrisGraphReasonDrawDepthStencilAliasMismatch = 16;
constexpr jlong kIrisGraphReasonDrawBufferResidencyMismatch = 17;
constexpr jlong kIrisGraphReasonDrawTextureOverrideMismatch = 18;
constexpr jlong kIrisGraphReasonDrawSharedTextureMismatch = 19;
constexpr jlong kIrisGraphReasonDrawSharedTextureFenceTimeout = 20;
constexpr jlong kIrisGraphReasonDrawTextureUploadUnsupported = 21;
constexpr jlong kIrisGraphReasonDrawUnusedTextureOverride = 22;
constexpr jlong kIrisGraphReasonDrawArgumentBindingUnsupported = 23;
constexpr jlong kIrisGraphReasonDrawIndexBufferMissing = 24;
constexpr jlong kIrisGraphReasonDrawIndexRangeInvalid = 25;
constexpr jlong kIrisGraphReasonDrawTextureSubresourceUnsupported = 26;
constexpr jlong kIrisGraphReasonDrawTextureFormatUnsupported = 27;
constexpr jlong kIrisGraphReasonDrawTextureByteBudgetExceeded = 28;
constexpr jlong kIrisGraphReasonReadbackTextureMissing = 29;
constexpr jlong kIrisGraphReasonReadbackMultisampleUnsupported = 30;
constexpr jlong kIrisGraphReasonReadbackFormatUnsupported = 31;
constexpr jlong kIrisGraphReasonReadbackRowSizeUnsupported = 32;
constexpr jlong kIrisGraphReasonReadbackByteSizeUnsupported = 33;
constexpr jlong kIrisGraphReasonClearRegionUnsupported = 34;
constexpr jlong kIrisGraphReasonClearTextureMissing = 35;
constexpr jlong kIrisGraphReasonClearColorFormatUnsupported = 36;
constexpr jlong kIrisGraphReasonClearDepthFormatUnsupported = 37;
constexpr jlong kIrisGraphReasonClearStencilFormatUnsupported = 38;
constexpr jlong kIrisGraphReasonCopyTextureMissing = 39;
constexpr jlong kIrisGraphReasonCopyFormatMismatch = 40;
constexpr jlong kIrisGraphReasonCopyMultisampleUnsupported = 41;
constexpr jlong kIrisGraphReasonCopyMipLevelUnsupported = 42;
constexpr jlong kIrisGraphReasonCopyBoundsUnsupported = 43;
constexpr jlong kIrisGraphReasonMipmapTextureMissing = 44;
constexpr jlong kIrisGraphReasonMipmapLevelsUnavailable = 45;
constexpr jlong kIrisGraphReasonMipmapMultisampleUnsupported = 46;
constexpr jlong kIrisGraphReasonPresentationTextureMissing = 47;
constexpr jlong kIrisGraphReasonPresentationMultisampleUnsupported = 48;
constexpr jlong kIrisGraphReasonPresentationFormatUnsupported = 49;
constexpr jlong kIrisGraphReasonPresentationSizeUnsupported = 50;
constexpr jlong kIrisGraphReasonPresentationQueueFull = 51;
constexpr jlong kIrisGraphReasonFrameInputResidentMissing = 52;
constexpr jlong kIrisGraphReasonDrawExternalBufferIndexMismatch = 53;
constexpr jlong kIrisGraphReasonDrawExternalBufferMissing = 54;
constexpr jlong kIrisGraphReasonDrawExternalBufferLengthMismatch = 55;
constexpr jlong kIrisGraphReasonDrawExternalTextureIndexMismatch = 56;
constexpr jlong kIrisGraphReasonDrawExternalTextureMissing = 57;
constexpr jlong kIrisGraphReasonDrawExternalTextureMetadataMismatch = 58;
constexpr jlong kIrisGraphReasonDrawFeedbackSnapshotUnsupported = 59;

struct IrisMetal4GraphFrameResource {
  uint32_t resourceId = 0;
  uint64_t token = 0;
};

struct IrisMetal4GraphFrameInputBuffer {
  uint32_t storageKind = 0;
  uint32_t byteLength = 0;
  uint64_t sharedHandle = 0;
  std::vector<uint8_t> bytes;
  const uint8_t *borrowedBytes = nullptr;
};

struct IrisMetal4GraphFramePreparedInputTexture {
  uint32_t glName = 0;
  std::string format;
  uint32_t width = 0;
  uint32_t height = 0;
  uint32_t layer = 0;
  uint32_t mipLevel = 0;
  uint32_t bytesPerPixel = 0;
  id<MTLTexture> texture = nil;
};

struct IrisMetal4GraphFrameOperation {
  uint32_t kind = 0;
  uint32_t firstResource = 0;
  uint32_t secondResource = 0;
  uint32_t aspect = 0;
  uint32_t valueKind = 0;
  uint32_t mipLevel = 0;
  std::vector<uint64_t> rawValues;
  bool hasRegion = false;
  int32_t x = 0;
  int32_t y = 0;
  int32_t width = 0;
  int32_t height = 0;
  uint32_t sourceLevel = 0;
  uint32_t destinationLevel = 0;
  int32_t destinationX = 0;
  int32_t destinationY = 0;
  uint32_t barrierBits = 0;
  std::string pipelineKey;
  std::vector<uint8_t> replayPacket;
  const uint8_t *borrowedReplayPacket = nullptr;
  uint32_t replayPacketLength = 0;
  std::vector<std::pair<uint32_t, uint32_t>> colorTargets;
  std::vector<uint32_t> colorTargetMips;
  int32_t depthResource = -1;
  uint32_t depthMipLevel = 0;
  int32_t stencilResource = -1;
  uint32_t stencilMipLevel = 0;
  std::unordered_map<uint32_t, uint32_t> textureOverrides;
  uint32_t groupsX = 0;
  uint32_t groupsY = 0;
  uint32_t groupsZ = 0;
  std::vector<uint32_t> resources;
};

static constexpr NSUInteger kIrisMetal4FrameArenaChunkBytes =
    2ULL * 1024ULL * 1024ULL;
static constexpr NSUInteger kIrisMetal4FrameArenaMaximumAllocationBytes =
    32ULL * 1024ULL * 1024ULL;
static constexpr size_t kIrisMetal4FrameArenaMaximumChunks = 16;
static constexpr size_t kIrisMetal4FrameArenaPoolMaximumBuffers = 32;
static constexpr uint64_t kIrisMetal4FrameArenaPoolMaximumBytes =
    64ULL * 1024ULL * 1024ULL;

class IrisMetal4FrameArenaBufferPool {
 public:
  id<MTLBuffer> acquire(NSUInteger length) {
    auto found = available_.find(length);
    if (found != available_.end() && !found->second.empty()) {
      id<MTLBuffer> buffer = found->second.back();
      found->second.pop_back();
      pooledBuffers_--;
      pooledBytes_ -= (uint64_t)length;
      return buffer;
    }
    return [g_device newBufferWithLength:length
                                 options:MTLResourceStorageModeShared];
  }

  void recycle(id<MTLBuffer> buffer) {
    if (!buffer)
      return;
    uint64_t length = (uint64_t)buffer.length;
    if (length == 0 || pooledBuffers_ >=
            kIrisMetal4FrameArenaPoolMaximumBuffers ||
        length > kIrisMetal4FrameArenaPoolMaximumBytes ||
        pooledBytes_ > kIrisMetal4FrameArenaPoolMaximumBytes - length) {
      [buffer release];
      return;
    }
    try {
      available_[(NSUInteger)length].push_back(buffer);
      pooledBuffers_++;
      pooledBytes_ += length;
    } catch (...) {
      [buffer release];
    }
  }

  ~IrisMetal4FrameArenaBufferPool() {
    for (auto &bucket : available_) {
      for (id<MTLBuffer> buffer : bucket.second)
        [buffer release];
    }
  }

 private:
  std::unordered_map<NSUInteger, std::vector<id<MTLBuffer>>> available_;
  size_t pooledBuffers_ = 0;
  uint64_t pooledBytes_ = 0;
};

static thread_local IrisMetal4FrameArenaBufferPool
    g_irisMetal4FrameArenaBufferPool;

struct IrisMetal4FrameArenaChunk {
  id<MTLBuffer> buffer = nil;
  NSUInteger used = 0;
};

class IrisMetal4FrameBufferArena {
 public:
  struct Slice {
    id<MTLBuffer> buffer = nil;
    NSUInteger offset = 0;
  };

  bool allocate(NSUInteger length, NSUInteger alignment, Slice &slice) {
    slice = Slice{};
    if (length == 0 || length > kIrisMetal4FrameArenaMaximumAllocationBytes)
      return false;
    alignment = std::max((NSUInteger)1, alignment);
    auto alignedOffset = [&](NSUInteger value) -> NSUInteger {
      NSUInteger remainder = value % alignment;
      if (remainder == 0)
        return value;
      NSUInteger padding = alignment - remainder;
      return value > NSUIntegerMax - padding ? NSUIntegerMax
                                             : value + padding;
    };
    if (!chunks_.empty()) {
      IrisMetal4FrameArenaChunk &chunk = chunks_.back();
      NSUInteger offset = alignedOffset(chunk.used);
      if (offset != NSUIntegerMax && offset <= chunk.buffer.length &&
          length <= chunk.buffer.length - offset) {
        chunk.used = offset + length;
        slice = {chunk.buffer, offset};
        return true;
      }
    }
    if (chunks_.size() >= kIrisMetal4FrameArenaMaximumChunks)
      return false;
    NSUInteger capacity = std::max(kIrisMetal4FrameArenaChunkBytes, length);
    constexpr NSUInteger pageAlignment = 4096;
    NSUInteger pageRemainder = capacity % pageAlignment;
    if (pageRemainder != 0) {
      NSUInteger padding = pageAlignment - pageRemainder;
      if (capacity > NSUIntegerMax - padding)
        return false;
      capacity += padding;
    }
    id<MTLBuffer> buffer = g_irisMetal4FrameArenaBufferPool.acquire(
        capacity);
    if (!buffer)
      return false;
    try {
      chunks_.push_back({buffer, length});
    } catch (...) {
      g_irisMetal4FrameArenaBufferPool.recycle(buffer);
      return false;
    }
    slice = {buffer, 0};
    return true;
  }

  size_t bufferCount() const { return chunks_.size(); }

  void appendResidencyAllocations(
      std::vector<id<MTLAllocation>> &allocations) const
      API_AVAILABLE(macos(26.0)) {
    for (const IrisMetal4FrameArenaChunk &chunk : chunks_) {
      if (chunk.buffer)
        allocations.push_back((id<MTLAllocation>)chunk.buffer);
    }
  }

  std::vector<IrisMetal4FrameArenaChunk> takeChunks() {
    return std::move(chunks_);
  }

  void abandon() { chunks_.clear(); }

  ~IrisMetal4FrameBufferArena() {
    for (IrisMetal4FrameArenaChunk &chunk : chunks_)
      g_irisMetal4FrameArenaBufferPool.recycle(chunk.buffer);
  }

 private:
  std::vector<IrisMetal4FrameArenaChunk> chunks_;
};

// Draw preparation is implemented next to the proven MRX7 replay helpers
// below. Keep the graph executor above that implementation so the ABI parser
// remains adjacent to its JNI entry point, while exposing only an opaque
// prepared-draw lifetime here.
struct IrisMetal4GraphPreparedDraw;
static bool iris_graph_prepare_input_textures(
    const std::vector<IrisShadowTexture> &inputs,
    std::vector<IrisMetal4GraphFramePreparedInputTexture> &textures,
    jlong &reason) API_AVAILABLE(macos(26.0));
static IrisMetal4GraphPreparedDraw *iris_graph_prepare_draw(
    const IrisMetal4GraphFrameOperation &operation,
    const std::unordered_map<uint32_t, id<MTLTexture>> &graphTextures,
    const std::vector<id<MTLBuffer>> &graphInputBuffers,
    const std::vector<IrisMetal4GraphFramePreparedInputTexture>
        &graphInputTextures,
    IrisMetal4FrameBufferArena *frameArena,
    std::vector<uint64_t> &inputSurfaceLeases,
    int &outcome, jlong &reason) API_AVAILABLE(macos(26.0));
static NSUInteger iris_graph_prepared_draw_allocation_count(
    const IrisMetal4GraphPreparedDraw *draw) API_AVAILABLE(macos(26.0));
static void iris_graph_prepared_draw_append_residency_allocations(
    const IrisMetal4GraphPreparedDraw *draw,
    std::vector<id<MTLAllocation>> &allocations)
    API_AVAILABLE(macos(26.0));
static bool iris_graph_prepared_draw_can_share_pass(
    const IrisMetal4GraphPreparedDraw *first,
    const IrisMetal4GraphPreparedDraw *next) API_AVAILABLE(macos(26.0));
static bool iris_graph_presentation_source_level(
    const IrisMetal4GraphFramePacket &packet, uint32_t resourceId,
    uint32_t &level) {
  for (size_t operationIndex = packet.operations.size();
       operationIndex > 0; operationIndex--) {
    const auto &operation = packet.operations[operationIndex - 1];
    if (operation.kind != 5)
      continue;
    for (size_t index = 0; index < operation.colorTargets.size(); index++) {
      if (operation.colorTargets[index].second != resourceId)
        continue;
      level = index < operation.colorTargetMips.size()
          ? operation.colorTargetMips[index] : 0;
      return true;
    }
  }
  return false;
}

static NSUInteger iris_graph_draw_color_target_mip(
    const IrisMetal4GraphFrameOperation &operation, uint32_t slot)
    API_AVAILABLE(macos(26.0)) {
  for (size_t index = 0; index < operation.colorTargets.size(); index++) {
    if (operation.colorTargets[index].first == slot &&
        index < operation.colorTargetMips.size()) {
      return operation.colorTargetMips[index];
    }
  }
  return 0;
}

static int iris_graph_encode_prepared_draw_run(
    std::vector<IrisMetal4GraphPreparedDraw *> &draws,
    size_t begin, size_t end,
    id<MTL4CommandBuffer> commandBuffer, bool applyBarrier,
    MTLStages graphStages,
    const IrisMetal4GraphFrameOperation &drawOperation,
    const std::vector<const IrisMetal4GraphFrameOperation *> &loadClears,
    jlong &reason) API_AVAILABLE(macos(26.0));
static void iris_graph_destroy_prepared_draw(
    IrisMetal4GraphPreparedDraw *draw) API_AVAILABLE(macos(26.0));

struct IrisMetal4GraphFramePacket {
  uint64_t contextGeneration = 0;
  int32_t readbackResourceId = -1;
  int32_t presentationResourceId = -1;
  std::vector<IrisMetal4GraphFrameResource> resources;
  std::vector<IrisMetal4GraphFrameInputBuffer> inputBuffers;
  std::vector<IrisShadowTexture> inputTextures;
  std::vector<IrisMetal4GraphFrameOperation> operations;
};

static bool iris_graph_read_i32(IrisPipelineByteReader &reader,
                                int32_t &value) {
  uint32_t encoded = 0;
  if (!reader.u32(encoded))
    return false;
  value = (int32_t)encoded;
  return true;
}

static bool parse_iris_metal4_graph_frame(
    const uint8_t *bytes, size_t byteLength,
    IrisMetal4GraphFramePacket &result, bool borrowPayloads) {
  if (!bytes || byteLength == 0)
    return false;
  IrisPipelineByteReader reader(bytes, byteLength);
  uint32_t magic = 0;
  uint32_t schema = 0;
  uint32_t count = 0;
  if (!reader.u32(magic) || magic != kIrisMetal4GraphFrameMagic ||
      !reader.u32(schema) ||
      (schema != kIrisMetal4GraphFrameSchema &&
       schema != kIrisMetal4GraphFrameLegacySchema) ||
      !reader.u64(result.contextGeneration) ||
      result.contextGeneration == 0 ||
      !iris_graph_read_i32(reader, result.readbackResourceId) ||
      result.readbackResourceId < -1 ||
      !iris_graph_read_i32(reader, result.presentationResourceId) ||
      result.presentationResourceId < -1 ||
      (result.readbackResourceId >= 0 &&
       result.presentationResourceId >= 0) || !reader.u32(count) ||
      count == 0 || count > kIrisMetal4GraphTextureLimit) {
    return false;
  }
  try {
    result.resources.resize(count);
  } catch (...) {
    return false;
  }
  static thread_local std::unordered_set<uint32_t> resourceIds;
  static thread_local std::unordered_set<uint64_t> resourceTokens;
  resourceIds.clear();
  resourceTokens.clear();
  resourceIds.reserve(count);
  resourceTokens.reserve(count);
  for (auto &resource : result.resources) {
    if (!reader.u32(resource.resourceId) || resource.resourceId >= 16384 ||
        !reader.u64(resource.token) || resource.token == 0 ||
        !resourceIds.emplace(resource.resourceId).second ||
        !resourceTokens.emplace(resource.token).second) {
      return false;
    }
  }
  if (result.readbackResourceId >= 0 &&
      resourceIds.find((uint32_t)result.readbackResourceId) ==
          resourceIds.end()) {
    return false;
  }
  if (result.presentationResourceId >= 0 &&
      resourceIds.find((uint32_t)result.presentationResourceId) ==
          resourceIds.end()) {
    return false;
  }
  uint64_t totalInputBufferBytes = 0;
  if (!read_bounded_count(reader, kIrisShadowReplayMaximumBuffers, count)) {
    return false;
  }
  try {
    result.inputBuffers.resize(count);
  } catch (...) {
    return false;
  }
  for (auto &buffer : result.inputBuffers) {
    buffer.borrowedBytes = nullptr;
    buffer.bytes.clear();
    if (!reader.u32(buffer.storageKind) || buffer.storageKind < 1 ||
        buffer.storageKind > 2 || !reader.u32(buffer.byteLength) ||
        buffer.byteLength == 0 ||
        !iris_shadow_add_bounded(totalInputBufferBytes, buffer.byteLength,
                                 kIrisShadowReplayMaximumBufferBytes)) {
      return false;
    }
    if (buffer.storageKind == 1) {
      bool read = borrowPayloads
          ? reader.bytesView(buffer.byteLength, buffer.borrowedBytes)
          : reader.bytes(buffer.byteLength, buffer.bytes);
      if (!read) {
        return false;
      }
    } else if (!reader.u64(buffer.sharedHandle) ||
               buffer.sharedHandle == 0) {
      return false;
    }
  }
  uint64_t totalInputTextureBytes = 0;
  if (!read_bounded_count(reader, kIrisShadowReplayMaximumTextures,
                          count)) {
    return false;
  }
  try {
    result.inputTextures.resize(count);
  } catch (...) {
    return false;
  }
  static thread_local std::unordered_set<uint32_t> inputTextureNames;
  inputTextureNames.clear();
  inputTextureNames.reserve(count);
  for (auto &texture : result.inputTextures) {
    if (!reader.u32(texture.glName) || texture.glName == 0 ||
        !inputTextureNames.emplace(texture.glName).second ||
        !reader.string(texture.format) || !reader.u32(texture.width) ||
        texture.width == 0 || texture.width > 16384 ||
        !reader.u32(texture.height) || texture.height == 0 ||
        texture.height > 16384 || !reader.u32(texture.layer) ||
        !reader.u32(texture.mipLevel) ||
        !reader.u32(texture.bytesPerPixel) ||
        texture.bytesPerPixel == 0 || texture.bytesPerPixel > 16 ||
        !reader.u32(texture.storageKind) || texture.storageKind != 1) {
      return false;
    }
    uint64_t expected = (uint64_t)texture.width * texture.height;
    if (expected > UINT64_MAX / texture.bytesPerPixel)
      return false;
    expected *= texture.bytesPerPixel;
    uint32_t length = 0;
    if (!reader.u32(length) || expected != length ||
        !iris_shadow_add_bounded(totalInputTextureBytes, length,
                                 kIrisShadowReplayMaximumTextureBytes) ||
        !reader.bytes(length, texture.bytes)) {
      return false;
    }
  }
  if (!reader.u32(count) || count == 0 || count > 262144) {
    return false;
  }
  try {
    result.operations.resize(count);
  } catch (...) {
    return false;
  }
  for (auto &operation : result.operations) {
    operation.rawValues.clear();
    operation.mipLevel = 0;
    operation.pipelineKey.clear();
    operation.replayPacket.clear();
    operation.borrowedReplayPacket = nullptr;
    operation.replayPacketLength = 0;
    operation.colorTargets.clear();
    operation.colorTargetMips.clear();
    operation.depthResource = -1;
    operation.depthMipLevel = 0;
    operation.stencilResource = -1;
    operation.stencilMipLevel = 0;
    operation.textureOverrides.clear();
    operation.resources.clear();
    if (!reader.u32(operation.kind) || operation.kind < 1 ||
        operation.kind > 6) {
      return false;
    }
    if (operation.kind == 1) {
      uint32_t valueCount = 0;
      if (!reader.u32(operation.firstResource) ||
          resourceIds.find(operation.firstResource) == resourceIds.end() ||
          (schema < kIrisMetal4GraphFrameSchema
              ? (operation.mipLevel = 0, true)
              : reader.u32(operation.mipLevel) &&
                  operation.mipLevel <= 15) ||
          !reader.u32(operation.aspect) || operation.aspect > 3 ||
          !reader.u32(operation.valueKind) || operation.valueKind > 3 ||
          !reader.u32(valueCount) || valueCount == 0 || valueCount > 4) {
        return false;
      }
      uint32_t expected = operation.aspect == 0 ? 4 :
          (operation.aspect == 3 ? 2 : 1);
      if (valueCount != expected ||
          (operation.aspect == 0 && operation.valueKind == 1) ||
          (operation.aspect == 1 && operation.valueKind > 1) ||
          (operation.aspect == 2 && operation.valueKind < 2) ||
          (operation.aspect == 3 && operation.valueKind > 1)) {
        return false;
      }
      try {
        operation.rawValues.resize(valueCount);
      } catch (...) {
        return false;
      }
      for (uint64_t &value : operation.rawValues) {
        if (!reader.u64(value))
          return false;
      }
      if (!reader.boolean(operation.hasRegion))
        return false;
      if (operation.hasRegion &&
          (!iris_graph_read_i32(reader, operation.x) ||
           !iris_graph_read_i32(reader, operation.y) ||
           !iris_graph_read_i32(reader, operation.width) ||
           !iris_graph_read_i32(reader, operation.height) ||
           operation.width <= 0 || operation.height <= 0)) {
        return false;
      }
    } else if (operation.kind == 2) {
      if (!reader.u32(operation.barrierBits) ||
          operation.barrierBits > INT32_MAX) {
        return false;
      }
    } else if (operation.kind == 3) {
      if (!reader.u32(operation.firstResource) ||
          !reader.u32(operation.secondResource) ||
          operation.firstResource == operation.secondResource ||
          resourceIds.find(operation.firstResource) == resourceIds.end() ||
          resourceIds.find(operation.secondResource) == resourceIds.end() ||
          !reader.u32(operation.sourceLevel) ||
          !reader.u32(operation.destinationLevel) ||
          !iris_graph_read_i32(reader, operation.x) || operation.x < 0 ||
          !iris_graph_read_i32(reader, operation.y) || operation.y < 0 ||
          !iris_graph_read_i32(reader, operation.destinationX) ||
          operation.destinationX < 0 ||
          !iris_graph_read_i32(reader, operation.destinationY) ||
          operation.destinationY < 0 ||
          !iris_graph_read_i32(reader, operation.width) ||
          operation.width <= 0 ||
          !iris_graph_read_i32(reader, operation.height) ||
          operation.height <= 0) {
        return false;
      }
    } else if (operation.kind == 4) {
      if (!reader.u32(operation.firstResource) ||
          resourceIds.find(operation.firstResource) ==
              resourceIds.end()) {
        return false;
      }
    } else if (operation.kind == 5) {
      uint32_t packetLength = 0;
      uint32_t targetCount = 0;
      uint32_t overrideCount = 0;
      if (!reader.string(operation.pipelineKey) ||
          operation.pipelineKey.size() != 64 ||
          !reader.u32(packetLength) || packetLength == 0 ||
          packetLength > (uint32_t)kIrisShadowReplayMaximumPacketBytes ||
          !(borrowPayloads
              ? reader.bytesView(packetLength,
                  operation.borrowedReplayPacket)
              : reader.bytes(packetLength, operation.replayPacket)) ||
          !reader.u32(targetCount) || targetCount > 8) {
        return false;
      }
      operation.replayPacketLength = packetLength;
      for (char value : operation.pipelineKey) {
        if (!((value >= '0' && value <= '9') ||
              (value >= 'a' && value <= 'f'))) {
          return false;
        }
      }
      try {
        operation.colorTargets.resize(targetCount);
        operation.colorTargetMips.assign(targetCount, 0);
      } catch (...) {
        return false;
      }
      bool occupiedSlots[8] = {};
      for (auto &target : operation.colorTargets) {
        if (!reader.u32(target.first) || target.first >= 8 ||
            occupiedSlots[target.first] || !reader.u32(target.second) ||
            resourceIds.find(target.second) == resourceIds.end() ||
            (schema >= kIrisMetal4GraphFrameSchema &&
             (!reader.u32(operation.colorTargetMips[
                  &target - operation.colorTargets.data()])
              || operation.colorTargetMips[
                  &target - operation.colorTargets.data()] > 15))) {
          return false;
        }
        occupiedSlots[target.first] = true;
      }
      if (!iris_graph_read_i32(reader, operation.depthResource) ||
          operation.depthResource < -1 ||
          (operation.depthResource >= 0 &&
           resourceIds.find((uint32_t)operation.depthResource) ==
               resourceIds.end()) ||
          (schema < kIrisMetal4GraphFrameSchema
              ? (operation.depthMipLevel = 0, true)
              : reader.u32(operation.depthMipLevel) &&
                  operation.depthMipLevel <= 15) ||
          !iris_graph_read_i32(reader, operation.stencilResource) ||
          operation.stencilResource < -1 ||
          (operation.stencilResource >= 0 &&
           resourceIds.find((uint32_t)operation.stencilResource) ==
               resourceIds.end()) ||
          (schema < kIrisMetal4GraphFrameSchema
              ? (operation.stencilMipLevel = 0, true)
              : reader.u32(operation.stencilMipLevel) &&
                  operation.stencilMipLevel <= 15) ||
          (operation.colorTargets.empty() &&
           operation.depthResource < 0 && operation.stencilResource < 0) ||
          !reader.u32(overrideCount) || overrideCount > 256) {
        return false;
      }
      for (uint32_t index = 0; index < overrideCount; index++) {
        uint32_t glName = 0;
        uint32_t resourceId = 0;
        if (!reader.u32(glName) || glName == 0 ||
            !reader.u32(resourceId) ||
            resourceIds.find(resourceId) == resourceIds.end() ||
            !operation.textureOverrides.emplace(glName,
                                                 resourceId).second) {
          return false;
        }
      }
    } else if (operation.kind == 6) {
      if (!reader.string(operation.pipelineKey) ||
          operation.pipelineKey.size() != 64) {
        return false;
      }
      uint32_t packetLength = 0;
      if (!reader.u32(packetLength) || packetLength == 0 ||
          packetLength > (uint32_t)kIrisShadowReplayMaximumPacketBytes ||
          !(borrowPayloads
              ? reader.bytesView(packetLength,
                  operation.borrowedReplayPacket)
              : reader.bytes(packetLength, operation.replayPacket)) ||
          !reader.u32(operation.groupsX) ||
          !reader.u32(operation.groupsY) ||
          !reader.u32(operation.groupsZ) ||
          operation.groupsX == 0 || operation.groupsY == 0 ||
          operation.groupsZ == 0 ||
          operation.groupsX > 1048576 || operation.groupsY > 1048576 ||
          operation.groupsZ > 1048576) {
        return false;
      }
      uint32_t resourceCount = 0;
      if (!read_bounded_count(reader, 16384, resourceCount)) {
        return false;
      }
      try {
        operation.resources.resize(resourceCount);
      } catch (...) {
        return false;
      }
      static thread_local std::unordered_set<uint32_t> computeResourceIds;
      computeResourceIds.clear();
      computeResourceIds.reserve(resourceCount);
      for (uint32_t &resourceId : operation.resources) {
        if (!reader.u32(resourceId) ||
            resourceIds.find(resourceId) == resourceIds.end() ||
            !computeResourceIds.emplace(resourceId).second) {
          return false;
        }
      }
      operation.replayPacketLength = packetLength;
      for (char value : operation.pipelineKey) {
        if (!((value >= '0' && value <= '9') ||
              (value >= 'a' && value <= 'f'))) {
          return false;
        }
      }
    } else {
      uint32_t packetLength = 0;
      uint32_t targetCount = 0;
      uint32_t overrideCount = 0;
      if (!reader.string(operation.pipelineKey) ||
          operation.pipelineKey.size() != 64 ||
          !reader.u32(packetLength) || packetLength == 0 ||
          packetLength > (uint32_t)kIrisShadowReplayMaximumPacketBytes ||
          !(borrowPayloads
              ? reader.bytesView(packetLength,
                  operation.borrowedReplayPacket)
              : reader.bytes(packetLength, operation.replayPacket)) ||
          !reader.u32(targetCount) || targetCount > 8) {
        return false;
      }
      operation.replayPacketLength = packetLength;
      for (char value : operation.pipelineKey) {
        if (!((value >= '0' && value <= '9') ||
              (value >= 'a' && value <= 'f'))) {
          return false;
        }
      }
      try {
        operation.colorTargets.resize(targetCount);
        operation.colorTargetMips.assign(targetCount, 0);
      } catch (...) {
        return false;
      }
      bool occupiedSlots[8] = {};
      for (auto &target : operation.colorTargets) {
        if (!reader.u32(target.first) || target.first >= 8 ||
            occupiedSlots[target.first] || !reader.u32(target.second) ||
            resourceIds.find(target.second) == resourceIds.end() ||
            (schema >= kIrisMetal4GraphFrameSchema &&
             (!reader.u32(operation.colorTargetMips[
                  &target - operation.colorTargets.data()])
              || operation.colorTargetMips[
                  &target - operation.colorTargets.data()] > 15))) {
          return false;
        }
        occupiedSlots[target.first] = true;
      }
      if (!iris_graph_read_i32(reader, operation.depthResource) ||
          operation.depthResource < -1 ||
          (operation.depthResource >= 0 &&
           resourceIds.find((uint32_t)operation.depthResource) ==
               resourceIds.end()) ||
          !iris_graph_read_i32(reader, operation.stencilResource) ||
          operation.stencilResource < -1 ||
          (operation.stencilResource >= 0 &&
           resourceIds.find((uint32_t)operation.stencilResource) ==
               resourceIds.end()) ||
          (operation.colorTargets.empty() &&
           operation.depthResource < 0 && operation.stencilResource < 0) ||
          !reader.u32(overrideCount) || overrideCount > 256) {
        return false;
      }
      for (uint32_t index = 0; index < overrideCount; index++) {
        uint32_t glName = 0;
        uint32_t resourceId = 0;
        if (!reader.u32(glName) || glName == 0 ||
            !reader.u32(resourceId) ||
            resourceIds.find(resourceId) == resourceIds.end() ||
            !operation.textureOverrides.emplace(glName,
                                                 resourceId).second) {
          return false;
        }
      }
    }
  }
  return reader.done();
}

struct IrisMetal4GraphFrameRetainedResource {
  uint32_t resourceId = 0;
  id<MTLTexture> texture = nil;
};

static bool iris_graph_retire_submission(
    std::vector<IrisMetal4GraphPreparedDraw *> &preparedDraws,
    std::vector<IrisMetal4GraphFrameRetainedResource> &retained,
    IrisMetal4FrameBufferArena &frameArena,
    std::vector<id> &encodedObjects, id<MTLBuffer> &readback,
    id<MTLResidencySet> &residency,
    id<MTL4CommandAllocator> &allocator,
    id<MTL4CommandBuffer> &commandBuffer, MTL4CommitOptions *&options,
    const std::shared_ptr<Metal4ProbeState> &feedbackState)
    API_AVAILABLE(macos(26.0));

static jlongArray iris_graph_frame_result(
    JNIEnv *env, jlong status, jlong steps, jlong clears,
    jlong transfers, jlong barriers, jlong hash, jlong reason) {
  jlong values[] = {status, steps, clears, transfers, barriers, hash, reason};
  jlongArray result = env->NewLongArray(7);
  if (!result)
    return nullptr;
  env->SetLongArrayRegion(result, 0, 7, values);
  return env->ExceptionCheck() ? nullptr : result;
}

static bool iris_graph_frame_color_format(MTLPixelFormat format) {
  switch (format) {
  case MTLPixelFormatDepth16Unorm:
  case MTLPixelFormatDepth32Float:
  case MTLPixelFormatStencil8:
  case MTLPixelFormatDepth24Unorm_Stencil8:
  case MTLPixelFormatDepth32Float_Stencil8:
    return false;
  default:
    return format != MTLPixelFormatInvalid;
  }
}

static bool iris_graph_frame_depth_format(MTLPixelFormat format) {
  return format == MTLPixelFormatDepth16Unorm ||
         format == MTLPixelFormatDepth32Float ||
         format == MTLPixelFormatDepth24Unorm_Stencil8 ||
         format == MTLPixelFormatDepth32Float_Stencil8;
}

static bool iris_graph_frame_stencil_format(MTLPixelFormat format) {
  return format == MTLPixelFormatStencil8 ||
         format == MTLPixelFormatDepth24Unorm_Stencil8 ||
         format == MTLPixelFormatDepth32Float_Stencil8;
}

static bool iris_graph_frame_bytes_per_pixel(MTLPixelFormat format,
                                             uint32_t &bytes) {
  switch (format) {
  case MTLPixelFormatR8Unorm:
  case MTLPixelFormatR8Snorm:
  case MTLPixelFormatR8Uint:
  case MTLPixelFormatR8Sint: bytes = 1; return true;
  case MTLPixelFormatRG8Unorm:
  case MTLPixelFormatRG8Snorm:
  case MTLPixelFormatRG8Uint:
  case MTLPixelFormatRG8Sint:
  case MTLPixelFormatR16Unorm:
  case MTLPixelFormatR16Snorm:
  case MTLPixelFormatR16Uint:
  case MTLPixelFormatR16Sint:
  case MTLPixelFormatR16Float: bytes = 2; return true;
  case MTLPixelFormatRGBA8Unorm:
  case MTLPixelFormatRGBA8Unorm_sRGB:
  case MTLPixelFormatRGBA8Snorm:
  case MTLPixelFormatRGBA8Uint:
  case MTLPixelFormatRGBA8Sint:
  case MTLPixelFormatBGRA8Unorm:
  case MTLPixelFormatBGRA8Unorm_sRGB:
  case MTLPixelFormatRG16Unorm:
  case MTLPixelFormatRG16Snorm:
  case MTLPixelFormatRG16Uint:
  case MTLPixelFormatRG16Sint:
  case MTLPixelFormatRG16Float:
  case MTLPixelFormatR32Uint:
  case MTLPixelFormatR32Sint:
  case MTLPixelFormatR32Float:
  case MTLPixelFormatRGB10A2Unorm:
  case MTLPixelFormatRGB10A2Uint:
  case MTLPixelFormatRG11B10Float:
  case MTLPixelFormatRGB9E5Float: bytes = 4; return true;
  case MTLPixelFormatRGBA16Unorm:
  case MTLPixelFormatRGBA16Snorm:
  case MTLPixelFormatRGBA16Uint:
  case MTLPixelFormatRGBA16Sint:
  case MTLPixelFormatRGBA16Float:
  case MTLPixelFormatRG32Uint:
  case MTLPixelFormatRG32Sint:
  case MTLPixelFormatRG32Float: bytes = 8; return true;
  case MTLPixelFormatRGBA32Uint:
  case MTLPixelFormatRGBA32Sint:
  case MTLPixelFormatRGBA32Float: bytes = 16; return true;
  default: return false;
  }
}

static double iris_graph_frame_clear_component(uint32_t valueKind,
                                                uint64_t raw) {
  if (valueKind == 0) {
    uint32_t bits = (uint32_t)raw;
    float value = 0.0f;
    std::memcpy(&value, &bits, sizeof(value));
    return (double)value;
  }
  if (valueKind == 1) {
    double value = 0.0;
    std::memcpy(&value, &raw, sizeof(value));
    return value;
  }
  if (valueKind == 2)
    return (double)(int32_t)(uint32_t)raw;
  return (double)(uint32_t)raw;
}

static uint64_t iris_graph_frame_fnv1a64(const uint8_t *bytes,
                                         size_t length) {
  uint64_t hash = 1469598103934665603ULL;
  for (size_t index = 0; index < length; index++) {
    hash ^= bytes[index];
    hash *= 1099511628211ULL;
  }
  return hash;
}

static float iris_graph_decode_unsigned_float(uint32_t bits,
                                               uint32_t mantissaBits) {
  uint32_t exponent = bits >> mantissaBits;
  uint32_t mantissa = bits & ((1u << mantissaBits) - 1u);
  if (exponent == 0)
    return std::ldexp((float)mantissa, 1 - 15 - (int)mantissaBits);
  if (exponent == 31)
    return mantissa == 0 ? INFINITY : NAN;
  return std::ldexp(1.0f + (float)mantissa /
      (float)(1u << mantissaBits), (int)exponent - 15);
}

static uint8_t iris_graph_float_to_unorm8(float value) {
  if (std::isnan(value) || value <= 0.0f)
    return 0;
  if (!std::isfinite(value) || value >= 1.0f)
    return 255;
  return (uint8_t)std::lround(value * 255.0f);
}

// Exact-JAR diagnostics expose graph readbacks through one RGBA8 JNI getter.
// RG11B10F is decoded only after the GPU submission retires; render resources
// and shader sampling remain in their native packed HDR format.
static bool iris_graph_readback_rgba8(id<MTLTexture> texture,
                                      const uint8_t *bytes,
                                      size_t length,
                                      NSUInteger width,
                                      NSUInteger height,
                                      std::vector<uint8_t> &output) {
  if (!texture || !bytes || width == 0 || height == 0 ||
      width > SIZE_MAX / height || width * height > SIZE_MAX / 4)
    return false;
  size_t pixels = width * height;
  if (length != pixels * 4)
    return false;
  try {
    output.resize(pixels * 4);
  } catch (...) {
    output.clear();
    return false;
  }
  if (texture.pixelFormat == MTLPixelFormatRGBA8Unorm) {
    std::memcpy(output.data(), bytes, length);
    return true;
  }
  if (texture.pixelFormat != MTLPixelFormatRG11B10Float) {
    output.clear();
    return false;
  }
  for (size_t pixel = 0; pixel < pixels; ++pixel) {
    uint32_t packed = 0;
    std::memcpy(&packed, bytes + pixel * 4, sizeof(packed));
    float red = iris_graph_decode_unsigned_float(packed & 0x7ffu, 6);
    float green = iris_graph_decode_unsigned_float(
        (packed >> 11) & 0x7ffu, 6);
    float blue = iris_graph_decode_unsigned_float(
        (packed >> 22) & 0x3ffu, 5);
    output[pixel * 4] = iris_graph_float_to_unorm8(red);
    output[pixel * 4 + 1] = iris_graph_float_to_unorm8(green);
    output[pixel * 4 + 2] = iris_graph_float_to_unorm8(blue);
    output[pixel * 4 + 3] = 255;
  }
  return true;
}

static jlongArray run_iris_metal4_graph_frame(
    JNIEnv *env, jobject packetValue, jint directPacketLength,
    jint diagnosticReadbackMipLevel, bool requirePresentation,
    bool directPacket) {
  IrisMetal4GraphCpuTimer cpuTimer(requirePresentation);
  std::lock_guard<std::mutex> executionLock(g_irisMetal4ExecutionMutex);
  @autoreleasepool {
    g_irisMetal4LastGraphFrameRgba8.clear();
    if (!g_device || !g_metal4CommandQueue ||
        !g_metal4RuntimeVerified.load(std::memory_order_acquire)) {
      return iris_graph_frame_result(env, 0, 0, 0, 0, 0, 0, 1);
    }
    if (!packetValue || diagnosticReadbackMipLevel < 0 ||
        diagnosticReadbackMipLevel > 30)
      return iris_graph_frame_result(env, -1, 0, 0, 0, 0, 0, 0);
    if (@available(macOS 26.0, *)) {
      CFTimeInterval profileStarted = CACurrentMediaTime();
      CFTimeInterval profileParsed = 0.0;
      CFTimeInterval profileInputsReady = 0.0;
      CFTimeInterval profileDrawsReady = 0.0;
      CFTimeInterval profilePresentationReady = 0.0;
      CFTimeInterval profileResidencyCreated = 0.0;
      CFTimeInterval profileResidencyPopulated = 0.0;
      CFTimeInterval profileResidencyCommitted = 0.0;
      CFTimeInterval profileSetupReady = 0.0;
      CFTimeInterval profileEncoded = 0.0;
      CFTimeInterval profileCommitted = 0.0;
      uint64_t profileResidencyRawAllocations = 0;
      uint64_t profileResidencyUniqueAllocations = 0;
      uint64_t profileDrawOperations = 0;
      uint64_t profileDrawPasses = 0;
      uint64_t profileBarrierCalls = 0;
      jsize packetLength = 0;
      const uint8_t *packetBytes = nullptr;
      jbyte *criticalPacketBytes = nullptr;
      jbyteArray packetArray = nullptr;
      if (directPacket) {
        jlong directCapacity = env->GetDirectBufferCapacity(packetValue);
        void *directAddress = env->GetDirectBufferAddress(packetValue);
        if (!directAddress || directPacketLength <= 0 ||
            directCapacity < directPacketLength) {
          return iris_graph_frame_result(env, -1, 0, 0, 0, 0, 0, 0);
        }
        packetLength = directPacketLength;
        packetBytes = static_cast<const uint8_t *>(directAddress);
      } else {
        packetArray = static_cast<jbyteArray>(packetValue);
        packetLength = env->GetArrayLength(packetArray);
      }
      if (packetLength <= 0 ||
          packetLength > kIrisMetal4GraphFrameMaximumPacketBytes) {
        return iris_graph_frame_result(env, -1, 0, 0, 0, 0, 0, 0);
      }
      static thread_local IrisMetal4GraphFramePacket packet;
      if (!directPacket) {
        criticalPacketBytes = static_cast<jbyte *>(
            env->GetPrimitiveArrayCritical(packetArray, nullptr));
        if (!criticalPacketBytes)
          return iris_graph_frame_result(env, -1, 0, 0, 0, 0, 0, 0);
        packetBytes = reinterpret_cast<const uint8_t *>(
            criticalPacketBytes);
      }
      bool parsed = parse_iris_metal4_graph_frame(
          packetBytes, (size_t)packetLength, packet, directPacket);
      if (criticalPacketBytes) {
        env->ReleasePrimitiveArrayCritical(packetArray,
            criticalPacketBytes, JNI_ABORT);
      }
      if (!parsed)
        return iris_graph_frame_result(env, -1, 0, 0, 0, 0, 0, 0);
      if ((requirePresentation && packet.presentationResourceId < 0) ||
          (!requirePresentation && packet.presentationResourceId >= 0)) {
        return iris_graph_frame_result(env, -1, 0, 0, 0, 0, 0, 0);
      }
      if (requirePresentation && g_irisMetal4GraphPresentations.size() >=
              kIrisMetal4GraphPresentationLimit) {
        return iris_graph_frame_result(env, 0, 0, 0, 0, 0, 0,
            kIrisGraphReasonPresentationQueueFull);
      }
      if (!iris_metal4_retired_submission_slot_available())
        return iris_graph_frame_result(env, 0, 0, 0, 0, 0, 0, 7);
      profileParsed = CACurrentMediaTime();

      std::vector<IrisMetal4GraphFrameRetainedResource> retained;
      std::unordered_map<uint32_t, size_t> resourceIndex;
      {
        std::lock_guard<std::mutex> lock(g_irisMetal4GraphTextureMutex);
        try {
          retained.reserve(packet.resources.size());
        } catch (...) {
          return iris_graph_frame_result(env, -1, 0, 0, 0, 0, 0, 5);
        }
        for (const auto &resource : packet.resources) {
          id<MTLTexture> texture = nil;
          for (const auto &candidate : g_irisMetal4GraphTextures) {
            if (candidate.first.contextGeneration ==
                    packet.contextGeneration &&
                candidate.second.token == resource.token &&
                candidate.second.texture) {
              texture = [candidate.second.texture retain];
              break;
            }
          }
          if (!texture) {
            for (auto &value : retained)
              [value.texture release];
            return iris_graph_frame_result(env, 0, 0, 0, 0, 0, 0, 2);
          }
          resourceIndex.emplace(resource.resourceId, retained.size());
          retained.push_back({resource.resourceId, texture});
        }
      }
      auto releaseRetained = [&]() {
        for (auto &resource : retained) {
          if (resource.texture)
            [resource.texture release];
        }
      };
      auto textureFor = [&](uint32_t resourceId) -> id<MTLTexture> {
        auto found = resourceIndex.find(resourceId);
        return found == resourceIndex.end()
            ? nil : retained[found->second].texture;
      };
      std::unordered_map<uint32_t, id<MTLTexture>> graphTextures;
      try {
        graphTextures.reserve(retained.size());
        for (const auto &resource : retained)
          graphTextures.emplace(resource.resourceId, resource.texture);
      } catch (...) {
        releaseRetained();
        return iris_graph_frame_result(env, -1, 0, 0, 0, 0, 0, 5);
      }
      std::vector<id<MTLBuffer>> graphInputBuffers;
      try {
        graphInputBuffers.reserve(packet.inputBuffers.size());
      } catch (...) {
        releaseRetained();
        return iris_graph_frame_result(env, -1, 0, 0, 0, 0, 0, 5);
      }
      auto releaseGraphInputBuffers = [&]() {
        for (id<MTLBuffer> buffer : graphInputBuffers) {
          if (buffer)
            [buffer release];
        }
        graphInputBuffers.clear();
      };
      for (const auto &input : packet.inputBuffers) {
        id<MTLBuffer> buffer = nil;
        if (input.storageKind == 2) {
          id<MTLBuffer> resident = iris_metal4_resident_buffer(
              input.sharedHandle, input.byteLength);
          if (resident)
            buffer = [resident retain];
          if (!buffer) {
            dbg("WARN: Iris graph frame resident input missing token=%llu "
                "bytes=%u residentCount=%zu residentBytes=%llu\n",
                (unsigned long long)input.sharedHandle,
                input.byteLength, g_irisMetal4ResidentBuffers.size(),
                (unsigned long long)g_irisMetal4ResidentBufferBytes);
            releaseGraphInputBuffers();
            releaseRetained();
            return iris_graph_frame_result(env, 0, 0, 0, 0, 0, 0,
                kIrisGraphReasonFrameInputResidentMissing);
          }
        } else {
          buffer = [g_device newBufferWithLength:input.byteLength
                                          options:MTLResourceStorageModeShared];
          if (buffer) {
            const uint8_t *source = input.borrowedBytes
                ? input.borrowedBytes : input.bytes.data();
            std::memcpy(buffer.contents, source, input.byteLength);
          }
        }
        if (!buffer) {
          releaseGraphInputBuffers();
          releaseRetained();
          return iris_graph_frame_result(env, -1, 0, 0, 0, 0, 0, 5);
        }
        graphInputBuffers.push_back(buffer);
      }
      std::vector<IrisMetal4GraphFramePreparedInputTexture>
          graphInputTextures;
      auto releaseGraphInputTextures = [&]() {
        for (auto &input : graphInputTextures) {
          if (input.texture)
            [input.texture release];
        }
        graphInputTextures.clear();
      };
      jlong graphInputTextureReason = 0;
      if (!iris_graph_prepare_input_textures(packet.inputTextures,
              graphInputTextures, graphInputTextureReason)) {
        releaseGraphInputTextures();
        releaseGraphInputBuffers();
        releaseRetained();
        return iris_graph_frame_result(env, 0, 0, 0, 0, 0, 0,
            graphInputTextureReason != 0 ? graphInputTextureReason
                                         : (jlong)5);
      }
      profileInputsReady = CACurrentMediaTime();
      auto releaseGraphInputs = [&]() {
        releaseGraphInputTextures();
        releaseGraphInputBuffers();
      };
      IrisMetal4FrameBufferArena frameArgumentArena;
      std::vector<uint64_t> inputSurfaceLeases;
      std::vector<IrisMetal4GraphPreparedDraw *> preparedDraws(
          packet.operations.size(), nullptr);
      auto releasePreparedDraws = [&]() {
        for (IrisMetal4GraphPreparedDraw *draw : preparedDraws) {
          if (draw)
            iris_graph_destroy_prepared_draw(draw);
        }
      };
      std::vector<id> encodedObjects;
      try {
        encodedObjects.reserve(packet.operations.size());
      } catch (...) {
        releasePreparedDraws();
        releaseGraphInputs();
        releaseRetained();
        return iris_graph_frame_result(env, -1, 0, 0, 0, 0, 0, 5);
      }
      auto releaseEncodedObjects = [&]() {
        for (id object : encodedObjects) {
          if (object)
            [object release];
        }
        encodedObjects.clear();
      };

      id<MTL4CommandAllocator> allocator = nil;
      id<MTL4CommandBuffer> commandBuffer = nil;
      id<MTLResidencySet> residency = nil;
      id<MTLBuffer> readback = nil;
      id<MTLTexture> presentationTexture = nil;
      IOSurfaceRef presentationSurface = nullptr;
      MTL4CommitOptions *options = nil;
      jlong status = -1;
      jlong reason = 0;
      jlong clears = 0;
      jlong transfers = 0;
      jlong barriers = 0;
      uint64_t outputHash = 0;
      NSUInteger readbackRowBytes = 0;
      NSUInteger readbackLength = 0;
      NSUInteger readbackWidth = 0;
      NSUInteger readbackHeight = 0;
      bool graphReadbackCaptureFailed = false;
      bool submissionCommitted = false;
      bool presentationQueued = false;
      bool presentationTextureRetained = false;
      uint64_t presentationToken = 0;
      uint32_t presentationWidth = 0;
      uint32_t presentationHeight = 0;
      std::shared_ptr<Metal4ProbeState> submissionState;
      @try {
        NSUInteger preparedAllocationCount = 0;
        for (size_t operationIndex = 0;
             operationIndex < packet.operations.size(); operationIndex++) {
          const auto &operation = packet.operations[operationIndex];
          if (operation.kind != 5 && operation.kind != 6)
            continue;
          int outcome = -1;
          jlong drawReason = 0;
          IrisMetal4GraphPreparedDraw *draw = iris_graph_prepare_draw(
              operation, graphTextures, graphInputBuffers,
              graphInputTextures, &frameArgumentArena,
              inputSurfaceLeases, outcome, drawReason);
          if (!draw) {
            dbg("WARN: Iris graph draw preparation rejected operation=%zu "
                "reason=%lld pipeline=%.12s\n", operationIndex,
                (long long)drawReason, operation.pipelineKey.c_str());
            status = outcome == 0 ? 0 : -1;
            reason = drawReason != 0 ? drawReason
                                     : (outcome == 0 ? 6 : 5);
            @throw [NSException
                exceptionWithName:outcome == 0
                    ? @"MetalRenderGraphUnsupported"
                    : @"MetalRenderGraphSetup"
                           reason:@"graph draw preparation failed"
                         userInfo:nil];
          }
          preparedDraws[operationIndex] = draw;
          NSUInteger allocationCount =
              iris_graph_prepared_draw_allocation_count(draw);
          if (preparedAllocationCount > NSUIntegerMax - allocationCount) {
            status = -1;
            reason = 5;
            @throw [NSException exceptionWithName:@"MetalRenderGraphSetup"
                                           reason:@"draw residency overflow"
                                         userInfo:nil];
          }
          preparedAllocationCount += allocationCount;
        }
        profileDrawsReady = CACurrentMediaTime();
        id<MTLTexture> readbackTexture = packet.readbackResourceId >= 0
            ? textureFor((uint32_t)packet.readbackResourceId) : nil;
        if (packet.readbackResourceId >= 0) {
          uint32_t bytesPerPixel = 0;
          if (!readbackTexture) {
            status = 0;
            reason = kIrisGraphReasonReadbackTextureMissing;
            @throw [NSException
                exceptionWithName:@"MetalRenderGraphUnsupported"
                           reason:@"graph readback texture missing"
                         userInfo:nil];
          }
          if (readbackTexture.sampleCount != 1) {
            status = 0;
            reason = kIrisGraphReasonReadbackMultisampleUnsupported;
            @throw [NSException
                exceptionWithName:@"MetalRenderGraphUnsupported"
                           reason:@"graph readback multisample unsupported"
                         userInfo:nil];
          }
          if ((NSUInteger)diagnosticReadbackMipLevel >=
              readbackTexture.mipmapLevelCount) {
            status = 0;
            reason = kIrisGraphReasonReadbackFormatUnsupported;
            @throw [NSException
                exceptionWithName:@"MetalRenderGraphUnsupported"
                           reason:@"graph readback mip level unavailable"
                         userInfo:nil];
          }
          if (!iris_graph_frame_bytes_per_pixel(
                  readbackTexture.pixelFormat, bytesPerPixel)) {
            status = 0;
            reason = kIrisGraphReasonReadbackFormatUnsupported;
            @throw [NSException
                exceptionWithName:@"MetalRenderGraphUnsupported"
                           reason:@"graph readback format unsupported"
                         userInfo:nil];
          }
          readbackWidth = std::max((NSUInteger)1,
              readbackTexture.width >> diagnosticReadbackMipLevel);
          readbackHeight = std::max((NSUInteger)1,
              readbackTexture.height >> diagnosticReadbackMipLevel);
          if (readbackWidth > SIZE_MAX / bytesPerPixel) {
            status = 0;
            reason = kIrisGraphReasonReadbackRowSizeUnsupported;
            @throw [NSException
                exceptionWithName:@"MetalRenderGraphUnsupported"
                           reason:@"graph readback row size unsupported"
                         userInfo:nil];
          }
          readbackRowBytes = readbackWidth * bytesPerPixel;
          if (readbackHeight > SIZE_MAX / readbackRowBytes) {
            status = 0;
            reason = kIrisGraphReasonReadbackByteSizeUnsupported;
            @throw [NSException
                exceptionWithName:@"MetalRenderGraphUnsupported"
                           reason:@"graph readback size unsupported"
                         userInfo:nil];
          }
          readbackLength = readbackRowBytes * readbackHeight;
          readback = [g_device newBufferWithLength:readbackLength
                                           options:MTLResourceStorageModeShared];
          if (!readback)
            @throw [NSException exceptionWithName:@"MetalRenderGraphSetup"
                                           reason:@"readback allocation failed"
                                         userInfo:nil];
        }

        if (packet.presentationResourceId >= 0) {
          id<MTLTexture> source = textureFor(
              (uint32_t)packet.presentationResourceId);
          uint32_t presentationSourceMip = 0;
          if (!source || !iris_graph_presentation_source_level(
                  packet, (uint32_t)packet.presentationResourceId,
                  presentationSourceMip)) {
            status = 0;
            reason = kIrisGraphReasonPresentationTextureMissing;
            @throw [NSException
                exceptionWithName:@"MetalRenderGraphUnsupported"
                           reason:@"graph presentation texture missing"
                         userInfo:nil];
          }
          if (source.sampleCount != 1) {
            status = 0;
            reason = kIrisGraphReasonPresentationMultisampleUnsupported;
            @throw [NSException
                exceptionWithName:@"MetalRenderGraphUnsupported"
                           reason:@"graph presentation multisample unsupported"
                         userInfo:nil];
          }
          if (source.pixelFormat != MTLPixelFormatRGBA8Unorm ||
              source.textureType != MTLTextureType2D || source.depth != 1 ||
              source.arrayLength != 1) {
            status = 0;
            reason = kIrisGraphReasonPresentationFormatUnsupported;
            @throw [NSException
                exceptionWithName:@"MetalRenderGraphUnsupported"
                           reason:@"graph presentation format unsupported"
                         userInfo:nil];
          }
          if (presentationSourceMip >= source.mipmapLevelCount) {
            status = 0;
            reason = kIrisGraphReasonPresentationSizeUnsupported;
            @throw [NSException
                exceptionWithName:@"MetalRenderGraphUnsupported"
                           reason:@"graph presentation mip unavailable"
                         userInfo:nil];
          }
          NSUInteger sourceWidth = std::max((NSUInteger)1,
              source.width >> presentationSourceMip);
          NSUInteger sourceHeight = std::max((NSUInteger)1,
              source.height >> presentationSourceMip);
          if (sourceWidth == 0 || sourceHeight == 0 ||
              sourceWidth > UINT32_MAX || sourceHeight > UINT32_MAX ||
              sourceWidth > SIZE_MAX / 4u) {
            status = 0;
            reason = kIrisGraphReasonPresentationSizeUnsupported;
            @throw [NSException
                exceptionWithName:@"MetalRenderGraphUnsupported"
                           reason:@"graph presentation size unsupported"
                         userInfo:nil];
          }
          size_t bytesPerRow = IOSurfaceAlignProperty(
              kIOSurfaceBytesPerRow, (size_t)sourceWidth * 4u);
          if (bytesPerRow == 0 ||
              sourceHeight > SIZE_MAX / bytesPerRow) {
            status = 0;
            reason = kIrisGraphReasonPresentationSizeUnsupported;
            @throw [NSException
                exceptionWithName:@"MetalRenderGraphUnsupported"
                           reason:@"graph presentation allocation unsupported"
                         userInfo:nil];
          }
          NSDictionary *surfaceProperties = @{
            (id)kIOSurfaceWidth : @(sourceWidth),
            (id)kIOSurfaceHeight : @(sourceHeight),
            (id)kIOSurfaceBytesPerElement : @4,
            (id)kIOSurfaceBytesPerRow : @(bytesPerRow),
            (id)kIOSurfaceAllocSize :
                @(bytesPerRow * (size_t)sourceHeight),
            (id)kIOSurfacePixelFormat : @((uint32_t)'BGRA'),
          };
          presentationSurface = iris_metal4_acquire_graph_surface(
              (uint32_t)sourceWidth, (uint32_t)sourceHeight);
          if (!presentationSurface) {
            presentationSurface = IOSurfaceCreate(
                (__bridge CFDictionaryRef)surfaceProperties);
          }
          MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
              texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                           width:source.width
                                          height:source.height
                                       mipmapped:NO];
          descriptor.storageMode = MTLStorageModeShared;
          descriptor.usage = MTLTextureUsageRenderTarget |
                             MTLTextureUsageShaderRead;
          if (presentationSurface) {
            presentationTexture = [g_device
                newTextureWithDescriptor:descriptor
                                iosurface:presentationSurface
                                    plane:0];
          }
          if (!presentationSurface || !presentationTexture) {
            @throw [NSException exceptionWithName:@"MetalRenderGraphSetup"
                                           reason:@"presentation allocation failed"
                                         userInfo:nil];
          }
          presentationWidth = (uint32_t)sourceWidth;
          presentationHeight = (uint32_t)sourceHeight;
          retained.push_back({UINT32_MAX, presentationTexture});
          presentationTextureRetained = true;
        }
        profilePresentationReady = CACurrentMediaTime();

        std::vector<id<MTLAllocation>> residencyAllocations;
        std::vector<id<MTLAllocation>> uniqueResidencyAllocations;
        std::unordered_set<const void *> seenResidencyAllocations;
        residencyAllocations.reserve(retained.size() +
                                     (readback ? 1 : 0) +
                                     preparedAllocationCount +
                                     frameArgumentArena.bufferCount());
        for (const auto &resource : retained) {
          if (resource.texture) {
            residencyAllocations.push_back(
                (id<MTLAllocation>)resource.texture);
          }
        }
        frameArgumentArena.appendResidencyAllocations(
            residencyAllocations);
        for (IrisMetal4GraphPreparedDraw *draw : preparedDraws) {
          if (draw) {
            iris_graph_prepared_draw_append_residency_allocations(
                draw, residencyAllocations);
          }
        }
        if (readback)
          residencyAllocations.push_back((id<MTLAllocation>)readback);
        uniqueResidencyAllocations.reserve(residencyAllocations.size());
        seenResidencyAllocations.reserve(residencyAllocations.size());
        for (id<MTLAllocation> allocation : residencyAllocations) {
          if (allocation && seenResidencyAllocations.emplace(
                                (const void *)allocation).second) {
            uniqueResidencyAllocations.push_back(allocation);
          }
        }
        profileResidencyRawAllocations = residencyAllocations.size();
        profileResidencyUniqueAllocations =
            uniqueResidencyAllocations.size();

        MTLResidencySetDescriptor *residencyDescriptor =
            [[MTLResidencySetDescriptor alloc] init];
        residencyDescriptor.label = @"MetalRender Iris graph frame";
        residencyDescriptor.initialCapacity =
            std::max((NSUInteger)1,
                     (NSUInteger)uniqueResidencyAllocations.size());
        NSError *residencyError = nil;
        residency = [g_device
            newResidencySetWithDescriptor:residencyDescriptor
                                    error:&residencyError];
        [residencyDescriptor release];
        if (!residency)
          @throw [NSException exceptionWithName:@"MetalRenderGraphSetup"
                                         reason:@"residency allocation failed"
                                       userInfo:nil];
        profileResidencyCreated = CACurrentMediaTime();
        if (!uniqueResidencyAllocations.empty()) {
          [residency addAllocations:uniqueResidencyAllocations.data()
                              count:uniqueResidencyAllocations.size()];
        }
        profileResidencyPopulated = CACurrentMediaTime();
        [residency commit];
        // The command buffer's useResidencySet: declaration guarantees that
        // Metal makes these allocations resident for this submission.
        // requestResidency is an eager, synchronous residency request; doing
        // both here serialized every Iris frame for several milliseconds.
        profileResidencyCommitted = CACurrentMediaTime();

        allocator = [g_device newCommandAllocator];
        commandBuffer = [g_device newCommandBuffer];
        if (!allocator || !commandBuffer)
          @throw [NSException exceptionWithName:@"MetalRenderGraphSetup"
                                         reason:@"command allocation failed"
                                       userInfo:nil];
        [commandBuffer beginCommandBufferWithAllocator:allocator];
        [commandBuffer useResidencySet:residency];
        profileSetupReady = CACurrentMediaTime();

        MTLStages graphStages = MTLStageVertex | MTLStageFragment |
            MTLStageDispatch | MTLStageBlit;
        // Metal 4 encoders from one command buffer may overlap unless an
        // explicit queue barrier orders their conflicting accesses. Track
        // both sides of the hazard: the older implementation only retained
        // writes, which covered RAW/WAW but allowed a later render target
        // write to race an earlier shader read (WAR).
        std::unordered_set<uint32_t> pendingReads;
        std::unordered_set<uint32_t> pendingWrites;
        bool explicitBarrierPending = false;
        auto readNeedsBarrier = [&](uint32_t resourceId) {
          return explicitBarrierPending ||
              pendingWrites.find(resourceId) != pendingWrites.end();
        };
        auto writeNeedsBarrier = [&](uint32_t resourceId) {
          return explicitBarrierPending ||
              pendingWrites.find(resourceId) != pendingWrites.end() ||
              pendingReads.find(resourceId) != pendingReads.end();
        };
        auto consumeBarrier = [&]() {
          pendingReads.clear();
          pendingWrites.clear();
          explicitBarrierPending = false;
        };
        auto clearMatchesDrawTarget = [](
            const IrisMetal4GraphFrameOperation &clear,
            const IrisMetal4GraphFrameOperation &draw) {
          if (clear.kind != 1 || draw.kind != 5)
            return false;
          if (clear.aspect == 0) {
            for (size_t index = 0; index < draw.colorTargets.size();
                 index++) {
              const auto &target = draw.colorTargets[index];
              uint32_t targetMip = index < draw.colorTargetMips.size()
                  ? draw.colorTargetMips[index] : 0;
              if (target.second == clear.firstResource &&
                  targetMip == clear.mipLevel) {
                return true;
              }
            }
            return false;
          }
          bool depth = (clear.aspect == 1 || clear.aspect == 3) &&
              draw.depthResource >= 0 &&
              (uint32_t)draw.depthResource == clear.firstResource &&
              draw.depthMipLevel == clear.mipLevel;
          bool stencil = (clear.aspect == 2 || clear.aspect == 3) &&
              draw.stencilResource >= 0 &&
              (uint32_t)draw.stencilResource == clear.firstResource &&
              draw.stencilMipLevel == clear.mipLevel;
          return clear.aspect == 3 ? depth && stencil : depth || stencil;
        };
        for (size_t operationIndex = 0;
             operationIndex < packet.operations.size(); operationIndex++) {
          const auto &operation = packet.operations[operationIndex];
          // Fold attachment clears (and barriers that only order those
          // clears) into the load actions of the immediately following draw
          // pass. Ambiguous sequences retain the conservative standalone
          // clear path below.
          if (operation.kind == 1) {
            size_t drawIndex = operationIndex;
            std::vector<const IrisMetal4GraphFrameOperation *> loadClears;
            uint64_t foldedBarriers = 0;
            while (drawIndex < packet.operations.size() &&
                   (packet.operations[drawIndex].kind == 1 ||
                    packet.operations[drawIndex].kind == 2)) {
              if (packet.operations[drawIndex].kind == 1) {
                loadClears.push_back(&packet.operations[drawIndex]);
              } else {
                foldedBarriers++;
              }
              drawIndex++;
            }
            bool fold = !loadClears.empty() &&
                drawIndex < packet.operations.size() &&
                packet.operations[drawIndex].kind == 5 &&
                preparedDraws[drawIndex] &&
                preparedDraws[drawIndex]->feedbackCopies.empty() &&
                std::all_of(loadClears.begin(), loadClears.end(),
                    [&](const auto *clear) {
                      return clearMatchesDrawTarget(
                          *clear, packet.operations[drawIndex]);
                    });
            if (fold) {
              size_t runEnd = drawIndex + 1;
              while (runEnd < packet.operations.size() &&
                     packet.operations[runEnd].kind == 5 &&
                     preparedDraws[runEnd] &&
                     preparedDraws[runEnd]->feedbackCopies.empty() &&
                     iris_graph_prepared_draw_can_share_pass(
                         preparedDraws[drawIndex],
                         preparedDraws[runEnd])) {
                runEnd++;
              }
              bool applyBarrier = explicitBarrierPending ||
                  foldedBarriers != 0;
              for (size_t index = drawIndex;
                   !applyBarrier && index < runEnd; index++) {
                const auto &draw = packet.operations[index];
                for (const auto &target : draw.colorTargets) {
                  if (writeNeedsBarrier(target.second)) {
                    applyBarrier = true;
                    break;
                  }
                }
                if (!applyBarrier && draw.depthResource >= 0) {
                  applyBarrier = writeNeedsBarrier(
                      (uint32_t)draw.depthResource);
                }
                if (!applyBarrier && draw.stencilResource >= 0) {
                  applyBarrier = writeNeedsBarrier(
                      (uint32_t)draw.stencilResource);
                }
                if (!applyBarrier) {
                  for (const auto &sampled : draw.textureOverrides) {
                    if (readNeedsBarrier(sampled.second)) {
                      applyBarrier = true;
                      break;
                    }
                  }
                }
              }
              int outcome = iris_graph_encode_prepared_draw_run(
                  preparedDraws, drawIndex, runEnd, commandBuffer,
                  applyBarrier, graphStages, packet.operations[drawIndex],
                  loadClears, reason);
              if (outcome <= 0) {
                dbg("WARN: Iris graph fused-clear draw rejected operation=%zu "
                    "runEnd=%zu reason=%lld pipeline=%.12s\n",
                    drawIndex, runEnd, (long long)reason,
                    packet.operations[drawIndex].pipelineKey.c_str());
                status = outcome == 0 ? 0 : -1;
                if (reason == 0)
                  reason = outcome == 0 ? 6 : 5;
                @throw [NSException
                    exceptionWithName:outcome == 0
                        ? @"MetalRenderGraphUnsupported"
                        : @"MetalRenderGraphSetup"
                               reason:@"fused-clear graph draw failed"
                             userInfo:nil];
              }
              if (applyBarrier)
                consumeBarrier();
              if (applyBarrier)
                profileBarrierCalls++;
              clears += loadClears.size();
              barriers += foldedBarriers;
              for (size_t index = drawIndex; index < runEnd; index++) {
                const auto &draw = packet.operations[index];
                for (const auto &sampled : draw.textureOverrides) {
                  pendingReads.insert(sampled.second);
                  pendingWrites.insert(sampled.second);
                }
                for (const auto &target : draw.colorTargets)
                  pendingWrites.insert(target.second);
                if (draw.depthResource >= 0)
                  pendingWrites.insert((uint32_t)draw.depthResource);
                if (draw.stencilResource >= 0)
                  pendingWrites.insert((uint32_t)draw.stencilResource);
              }
              g_irisMetal4PipelineDrawAttemptCount.fetch_add(
                  runEnd - drawIndex, std::memory_order_relaxed);
              profileDrawOperations += runEnd - drawIndex;
              profileDrawPasses++;
              operationIndex = runEnd - 1;
              continue;
            }
          }
          if (operation.kind == 1) {
            if (operation.hasRegion) {
              status = 0;
              reason = kIrisGraphReasonClearRegionUnsupported;
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"partial graph clear unsupported"
                           userInfo:nil];
            }
            id<MTLTexture> target = textureFor(operation.firstResource);
            bool color = operation.aspect == 0;
            bool depth = operation.aspect == 1 || operation.aspect == 3;
            bool stencil = operation.aspect == 2 || operation.aspect == 3;
            if (!target) {
              status = 0;
              reason = kIrisGraphReasonClearTextureMissing;
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"graph clear texture missing"
                           userInfo:nil];
            }
            if (color &&
                !iris_graph_frame_color_format(target.pixelFormat)) {
              status = 0;
              reason = kIrisGraphReasonClearColorFormatUnsupported;
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"graph color clear format unsupported"
                           userInfo:nil];
            }
            if (depth &&
                !iris_graph_frame_depth_format(target.pixelFormat)) {
              status = 0;
              reason = kIrisGraphReasonClearDepthFormatUnsupported;
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"graph depth clear format unsupported"
                           userInfo:nil];
            }
            if (stencil &&
                !iris_graph_frame_stencil_format(target.pixelFormat)) {
              status = 0;
              reason = kIrisGraphReasonClearStencilFormatUnsupported;
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"graph stencil clear format unsupported"
                           userInfo:nil];
            }
            MTL4RenderPassDescriptor *pass =
                [[MTL4RenderPassDescriptor alloc] init];
            if (operation.mipLevel >= target.mipmapLevelCount) {
              status = 0;
              reason = kIrisGraphReasonClearTextureMissing;
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"graph clear mip level unavailable"
                           userInfo:nil];
            }
            NSUInteger targetWidth = std::max((NSUInteger)1,
                target.width >> operation.mipLevel);
            NSUInteger targetHeight = std::max((NSUInteger)1,
                target.height >> operation.mipLevel);
            pass.defaultRasterSampleCount = target.sampleCount;
            pass.renderTargetWidth = targetWidth;
            pass.renderTargetHeight = targetHeight;
            if (color) {
              MTLRenderPassColorAttachmentDescriptor *attachment =
                  pass.colorAttachments[0];
              attachment.texture = target;
              attachment.level = operation.mipLevel;
              attachment.loadAction = MTLLoadActionClear;
              attachment.storeAction = MTLStoreActionStore;
              attachment.clearColor = MTLClearColorMake(
                  iris_graph_frame_clear_component(operation.valueKind,
                      operation.rawValues[0]),
                  iris_graph_frame_clear_component(operation.valueKind,
                      operation.rawValues[1]),
                  iris_graph_frame_clear_component(operation.valueKind,
                      operation.rawValues[2]),
                  iris_graph_frame_clear_component(operation.valueKind,
                      operation.rawValues[3]));
            }
            if (depth) {
              pass.depthAttachment.texture = target;
              pass.depthAttachment.level = operation.mipLevel;
              pass.depthAttachment.loadAction = MTLLoadActionClear;
              pass.depthAttachment.storeAction = MTLStoreActionStore;
              pass.depthAttachment.clearDepth =
                  iris_graph_frame_clear_component(operation.valueKind,
                      operation.rawValues[0]);
            }
            if (stencil) {
              pass.stencilAttachment.texture = target;
              pass.stencilAttachment.level = operation.mipLevel;
              pass.stencilAttachment.loadAction = MTLLoadActionClear;
              pass.stencilAttachment.storeAction = MTLStoreActionStore;
              size_t index = operation.aspect == 3 ? 1 : 0;
              pass.stencilAttachment.clearStencil =
                  (uint32_t)operation.rawValues[index];
            }
            id<MTL4RenderCommandEncoder> encoder =
                [commandBuffer renderCommandEncoderWithDescriptor:pass];
            encodedObjects.push_back(pass);
            if (!encoder)
              @throw [NSException exceptionWithName:@"MetalRenderGraphSetup"
                                             reason:@"clear encoder unavailable"
                                           userInfo:nil];
            bool applyBarrier = writeNeedsBarrier(
                operation.firstResource);
            if (applyBarrier) {
              [encoder barrierAfterQueueStages:graphStages
                                  beforeStages:graphStages
                             visibilityOptions:MTL4VisibilityOptionDevice];
              profileBarrierCalls++;
              consumeBarrier();
            }
            [encoder endEncoding];
            pendingWrites.insert(operation.firstResource);
            clears++;
          } else if (operation.kind == 2) {
            explicitBarrierPending = true;
            barriers++;
          } else if (operation.kind == 3) {
            id<MTLTexture> source = textureFor(operation.firstResource);
            id<MTLTexture> destination =
                textureFor(operation.secondResource);
            NSUInteger sourceWidth = source &&
                operation.sourceLevel < source.mipmapLevelCount
                    ? std::max((NSUInteger)1,
                        source.width >> operation.sourceLevel) : 0;
            NSUInteger sourceHeight = source &&
                operation.sourceLevel < source.mipmapLevelCount
                    ? std::max((NSUInteger)1,
                        source.height >> operation.sourceLevel) : 0;
            NSUInteger destinationWidth = destination &&
                operation.destinationLevel < destination.mipmapLevelCount
                    ? std::max((NSUInteger)1,
                        destination.width >> operation.destinationLevel) : 0;
            NSUInteger destinationHeight = destination &&
                operation.destinationLevel < destination.mipmapLevelCount
                    ? std::max((NSUInteger)1,
                        destination.height >> operation.destinationLevel) : 0;
            if (!source || !destination) {
              status = 0;
              reason = kIrisGraphReasonCopyTextureMissing;
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"graph texture copy target missing"
                           userInfo:nil];
            }
            if (source.pixelFormat != destination.pixelFormat) {
              status = 0;
              reason = kIrisGraphReasonCopyFormatMismatch;
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"graph texture copy format mismatch"
                           userInfo:nil];
            }
            if (source.sampleCount != 1 || destination.sampleCount != 1) {
              bool fullSurfaceResolve =
                  source.sampleCount > 1 && destination.sampleCount == 1
                  && operation.sourceLevel == 0
                  && operation.destinationLevel == 0
                  && operation.x == 0 && operation.y == 0
                  && operation.destinationX == 0
                  && operation.destinationY == 0
                  && operation.width == (int32_t)sourceWidth
                  && operation.height == (int32_t)sourceHeight
                  && operation.width == (int32_t)destinationWidth
                  && operation.height == (int32_t)destinationHeight
                  && source.mipmapLevelCount == 1
                  && destination.mipmapLevelCount == 1;
              if (!fullSurfaceResolve) {
                status = 0;
                reason = kIrisGraphReasonCopyMultisampleUnsupported;
                @throw [NSException
                    exceptionWithName:@"MetalRenderGraphUnsupported"
                               reason:@"graph texture copy multisample unsupported"
                             userInfo:nil];
              }
              MTLRenderPassDescriptor *pass =
                  [[MTLRenderPassDescriptor alloc] init];
              MTLRenderPassColorAttachmentDescriptor *attachment =
                  pass.colorAttachments[0];
              attachment.texture = source;
              attachment.level = operation.sourceLevel;
              attachment.loadAction = MTLLoadActionLoad;
              attachment.storeAction =
                  MTLStoreActionStoreAndMultisampleResolve;
              attachment.resolveTexture = destination;
              attachment.resolveLevel = operation.destinationLevel;
              pass.defaultRasterSampleCount = source.sampleCount;
              pass.renderTargetWidth = sourceWidth;
              pass.renderTargetHeight = sourceHeight;
              id<MTL4RenderCommandEncoder> encoder =
                  [commandBuffer renderCommandEncoderWithDescriptor:pass];
              encodedObjects.push_back(pass);
              if (!encoder)
                @throw [NSException
                    exceptionWithName:@"MetalRenderGraphSetup"
                               reason:@"resolve encoder unavailable"
                             userInfo:nil];
              bool applyBarrier = readNeedsBarrier(
                  operation.firstResource) || writeNeedsBarrier(
                      operation.secondResource);
              if (applyBarrier) {
                [encoder barrierAfterQueueStages:graphStages
                                    beforeStages:graphStages
                               visibilityOptions:MTL4VisibilityOptionDevice];
                profileBarrierCalls++;
                consumeBarrier();
              }
              [encoder endEncoding];
              pendingReads.insert(operation.firstResource);
              pendingWrites.insert(operation.secondResource);
              transfers++;
              continue;
            }
            if (operation.sourceLevel >= source.mipmapLevelCount ||
                operation.destinationLevel >=
                    destination.mipmapLevelCount) {
              status = 0;
              reason = kIrisGraphReasonCopyMipLevelUnsupported;
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"graph texture copy mip level unsupported"
                           userInfo:nil];
            }
            if ((uint64_t)operation.x + operation.width > sourceWidth ||
                (uint64_t)operation.y + operation.height > sourceHeight ||
                (uint64_t)operation.destinationX + operation.width >
                    destinationWidth ||
                (uint64_t)operation.destinationY + operation.height >
                    destinationHeight) {
              status = 0;
              reason = kIrisGraphReasonCopyBoundsUnsupported;
              dbg("WARN: Iris graph texture copy bounds rejected "
                  "operation=%zu src=%lux%lu@%u origin=%d,%d "
                  "dst=%lux%lu@%u origin=%d,%d size=%dx%d\n",
                  operationIndex, (unsigned long)sourceWidth,
                  (unsigned long)sourceHeight, operation.sourceLevel,
                  operation.x, operation.y,
                  (unsigned long)destinationWidth,
                  (unsigned long)destinationHeight,
                  operation.destinationLevel, operation.destinationX,
                  operation.destinationY, operation.width,
                  operation.height);
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"graph texture copy bounds unsupported"
                           userInfo:nil];
            }
            id<MTL4ComputeCommandEncoder> encoder =
                [commandBuffer computeCommandEncoder];
            if (!encoder)
              @throw [NSException exceptionWithName:@"MetalRenderGraphSetup"
                                             reason:@"copy encoder unavailable"
                                           userInfo:nil];
            bool applyBarrier = readNeedsBarrier(
                operation.firstResource) || writeNeedsBarrier(
                    operation.secondResource);
            if (applyBarrier) {
              [encoder barrierAfterQueueStages:graphStages
                                  beforeStages:graphStages
                             visibilityOptions:MTL4VisibilityOptionDevice];
              profileBarrierCalls++;
              consumeBarrier();
            }
            [encoder copyFromTexture:source
                         sourceSlice:0
                         sourceLevel:operation.sourceLevel
                        sourceOrigin:MTLOriginMake(operation.x, operation.y, 0)
                          sourceSize:MTLSizeMake(operation.width,
                                               operation.height, 1)
                           toTexture:destination
                    destinationSlice:0
                    destinationLevel:operation.destinationLevel
                   destinationOrigin:MTLOriginMake(operation.destinationX,
                                                   operation.destinationY, 0)];
            [encoder endEncoding];
            pendingReads.insert(operation.firstResource);
            pendingWrites.insert(operation.secondResource);
            transfers++;
          } else if (operation.kind == 4) {
            id<MTLTexture> texture = textureFor(operation.firstResource);
            if (!texture) {
              status = 0;
              reason = kIrisGraphReasonMipmapTextureMissing;
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"graph mipmap texture missing"
                           userInfo:nil];
            }
            if (texture.mipmapLevelCount <= 1) {
              status = 0;
              reason = kIrisGraphReasonMipmapLevelsUnavailable;
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"graph mipmap levels unavailable"
                           userInfo:nil];
            }
            if (texture.sampleCount != 1) {
              status = 0;
              reason = kIrisGraphReasonMipmapMultisampleUnsupported;
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"graph mipmap multisample unsupported"
                           userInfo:nil];
            }
            id<MTL4ComputeCommandEncoder> encoder =
                [commandBuffer computeCommandEncoder];
            if (!encoder)
              @throw [NSException exceptionWithName:@"MetalRenderGraphSetup"
                                             reason:@"mipmap encoder unavailable"
                                           userInfo:nil];
            bool applyBarrier = readNeedsBarrier(operation.firstResource) ||
                writeNeedsBarrier(operation.firstResource);
            if (applyBarrier) {
              [encoder barrierAfterQueueStages:graphStages
                                  beforeStages:MTLStageBlit
                             visibilityOptions:MTL4VisibilityOptionDevice];
              profileBarrierCalls++;
              consumeBarrier();
            }
            [encoder generateMipmapsForTexture:texture];
            [encoder endEncoding];
            pendingWrites.insert(operation.firstResource);
            transfers++;
          } else if (operation.kind == 6) {
            IrisMetal4GraphPreparedDraw *prepared =
                preparedDraws[operationIndex];
            if (!prepared || prepared->packet.draw.kind != 4 ||
                !prepared->pipeline.compute) {
              status = 0;
              reason = kIrisGraphReasonDrawPipelineUnavailable;
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphUnsupported"
                             reason:@"graph compute pipeline unavailable"
                           userInfo:nil];
            }
            bool applyBarrier = explicitBarrierPending;
            if (!applyBarrier) {
              for (uint32_t resourceId : operation.resources) {
                if (readNeedsBarrier(resourceId) ||
                    writeNeedsBarrier(resourceId)) {
                  applyBarrier = true;
                  break;
                }
              }
            }
            id<MTL4ComputeCommandEncoder> encoder =
                [commandBuffer computeCommandEncoder];
            if (!encoder)
              @throw [NSException
                  exceptionWithName:@"MetalRenderGraphSetup"
                             reason:@"compute encoder unavailable"
                           userInfo:nil];
            if (applyBarrier) {
              [encoder barrierAfterQueueStages:graphStages
                                  beforeStages:MTLStageDispatch
                             visibilityOptions:MTL4VisibilityOptionDevice];
              profileBarrierCalls++;
              consumeBarrier();
            }
            [encoder setComputePipelineState:prepared->pipeline.compute];
            if (prepared->resources.computeTable) {
              [encoder setArgumentTable:
                  (id<MTL4ArgumentTable>)prepared->resources.computeTable];
            }
            [encoder dispatchThreadgroups:
                MTLSizeMake(prepared->packet.draw.groupsX,
                            prepared->packet.draw.groupsY,
                            prepared->packet.draw.groupsZ)
                threadsPerThreadgroup:
                MTLSizeMake(prepared->packet.draw.localSizeX,
                            prepared->packet.draw.localSizeY,
                            prepared->packet.draw.localSizeZ)];
            [encoder endEncoding];
            for (uint32_t resourceId : operation.resources) {
              pendingReads.insert(resourceId);
              pendingWrites.insert(resourceId);
            }
            profileDrawOperations++;
          } else {
            size_t runEnd = operationIndex + 1;
            while (runEnd < packet.operations.size() &&
                   packet.operations[runEnd].kind == 5 &&
                   iris_graph_prepared_draw_can_share_pass(
                       preparedDraws[operationIndex],
                       preparedDraws[runEnd])) {
              runEnd++;
            }
            bool applyBarrier = explicitBarrierPending;
            for (size_t drawIndex = operationIndex;
                 !applyBarrier && drawIndex < runEnd; drawIndex++) {
              const auto &drawOperation = packet.operations[drawIndex];
              for (const auto &target : drawOperation.colorTargets) {
                if (writeNeedsBarrier(target.second)) {
                  applyBarrier = true;
                  break;
                }
              }
              if (!applyBarrier && drawOperation.depthResource >= 0) {
                applyBarrier = writeNeedsBarrier(
                    (uint32_t)drawOperation.depthResource);
              }
              if (!applyBarrier && drawOperation.stencilResource >= 0) {
                applyBarrier = writeNeedsBarrier(
                    (uint32_t)drawOperation.stencilResource);
              }
              if (!applyBarrier) {
                for (const auto &sampled :
                     drawOperation.textureOverrides) {
                  if (readNeedsBarrier(sampled.second)) {
                    applyBarrier = true;
                    break;
                  }
                }
              }
            }
            int outcome = iris_graph_encode_prepared_draw_run(
                preparedDraws, operationIndex, runEnd, commandBuffer,
                applyBarrier, graphStages, operation, {}, reason);
            if (outcome <= 0) {
              dbg("WARN: Iris graph draw encoding rejected operation=%zu "
                  "runEnd=%zu reason=%lld pipeline=%.12s\n",
                  operationIndex, runEnd, (long long)reason,
                  operation.pipelineKey.c_str());
              status = outcome == 0 ? 0 : -1;
              if (reason == 0)
                reason = outcome == 0 ? 6 : 5;
              @throw [NSException
                  exceptionWithName:outcome == 0
                      ? @"MetalRenderGraphUnsupported"
                      : @"MetalRenderGraphSetup"
                             reason:@"graph draw encoding failed"
                           userInfo:nil];
            }
            if (applyBarrier)
              consumeBarrier();
            if (applyBarrier)
              profileBarrierCalls++;
            for (size_t drawIndex = operationIndex;
                 drawIndex < runEnd; drawIndex++) {
              const auto &drawOperation = packet.operations[drawIndex];
              for (const auto &sampled : drawOperation.textureOverrides) {
                pendingReads.insert(sampled.second);
                pendingWrites.insert(sampled.second);
              }
              for (const auto &target : drawOperation.colorTargets)
                pendingWrites.insert(target.second);
              if (drawOperation.depthResource >= 0) {
                pendingWrites.insert(
                    (uint32_t)drawOperation.depthResource);
              }
              if (drawOperation.stencilResource >= 0) {
                pendingWrites.insert(
                    (uint32_t)drawOperation.stencilResource);
              }
            }
            g_irisMetal4PipelineDrawAttemptCount.fetch_add(
                runEnd - operationIndex, std::memory_order_relaxed);
            profileDrawOperations += runEnd - operationIndex;
            profileDrawPasses++;
            operationIndex = runEnd - 1;
          }
        }

        if (readback) {
          id<MTLTexture> texture =
              textureFor((uint32_t)packet.readbackResourceId);
          id<MTL4ComputeCommandEncoder> encoder =
              [commandBuffer computeCommandEncoder];
          if (!encoder)
            @throw [NSException exceptionWithName:@"MetalRenderGraphSetup"
                                           reason:@"readback encoder unavailable"
                                         userInfo:nil];
          // QA readback is an artificial consumer outside the graph packet.
          // Make every prior render/blit write visible before hashing it.
          [encoder barrierAfterQueueStages:graphStages
                              beforeStages:MTLStageBlit
                         visibilityOptions:MTL4VisibilityOptionDevice];
          profileBarrierCalls++;
          [encoder copyFromTexture:texture
                       sourceSlice:0
                       sourceLevel:(NSUInteger)diagnosticReadbackMipLevel
                      sourceOrigin:MTLOriginMake(0, 0, 0)
                        sourceSize:MTLSizeMake(readbackWidth,
                                             readbackHeight, 1)
                          toBuffer:readback
                 destinationOffset:0
            destinationBytesPerRow:readbackRowBytes
          destinationBytesPerImage:0];
          [encoder endEncoding];
        } else if (presentationTexture) {
          id<MTLTexture> source = textureFor(
              (uint32_t)packet.presentationResourceId);
          id<MTL4ComputeCommandEncoder> encoder =
              [commandBuffer computeCommandEncoder];
          if (!source || !encoder)
            @throw [NSException exceptionWithName:@"MetalRenderGraphSetup"
                                           reason:@"presentation encoder unavailable"
                                         userInfo:nil];
          // Presentation is an external consumer of the complete graph. Make
          // every prior render/blit write visible, then copy into the
          // IOSurface in the same GPU submission without a CPU readback.
          [encoder barrierAfterQueueStages:graphStages
                              beforeStages:MTLStageBlit
                         visibilityOptions:MTL4VisibilityOptionDevice];
          profileBarrierCalls++;
          [encoder copyFromTexture:source
                       sourceSlice:0
                       sourceLevel:presentationSourceMip
                      sourceOrigin:MTLOriginMake(0, 0, 0)
                        sourceSize:MTLSizeMake(presentationWidth,
                                             presentationHeight, 1)
                         toTexture:presentationTexture
                  destinationSlice:0
                  destinationLevel:0
                 destinationOrigin:MTLOriginMake(0, 0, 0)];
          [encoder endEncoding];
        }
        [commandBuffer endCommandBuffer];
        profileEncoded = CACurrentMediaTime();

        submissionState = std::make_shared<Metal4ProbeState>(allocator);
        if (presentationTexture) {
          options = iris_metal4_graph_timing_options();
          presentationToken =
              g_irisMetal4CutoverSurfaceSequence.fetch_add(
                  1, std::memory_order_relaxed);
          if (presentationToken == 0) {
            presentationToken =
                g_irisMetal4CutoverSurfaceSequence.fetch_add(
                    1, std::memory_order_relaxed);
          }
          if (g_irisMetal4GraphPresentations.capacity() <
              kIrisMetal4GraphPresentationLimit) {
            g_irisMetal4GraphPresentations.reserve(
                kIrisMetal4GraphPresentationLimit);
          }
          g_irisMetal4GraphPresentations.push_back({presentationToken,
              presentationWidth, presentationHeight, presentationSurface,
              submissionState});
          presentationSurface = nullptr;
          presentationQueued = true;
          submissionCommitted = metal4_commit_without_wait(
              (id<MTL4CommandQueue>)g_metal4CommandQueue,
              commandBuffer, submissionState, options);
          if (options) {
            [options release];
            options = nil;
          }
          if (!submissionCommitted) {
            IrisMetal4GraphPresentation &queued =
                g_irisMetal4GraphPresentations.back();
            presentationSurface = queued.surface;
            queued.surface = nullptr;
            g_irisMetal4GraphPresentations.pop_back();
            presentationQueued = false;
            status = -1;
            reason = 5;
          } else {
            outputHash = presentationToken;
            status = 1;
            reason = 0;
          }
        } else {
          submissionCommitted = true;
          bool completed = metal4_commit_and_wait(
              (id<MTL4CommandQueue>)g_metal4CommandQueue,
              commandBuffer, submissionState, 5'000);
          if (!completed ||
              !submissionState->completed.load(std::memory_order_acquire) ||
              !submissionState->succeeded.load(std::memory_order_acquire)) {
            status = -1;
            reason = 4;
          } else {
            if (readback) {
              outputHash = iris_graph_frame_fnv1a64(
                  (const uint8_t *)readback.contents, readbackLength);
              id<MTLTexture> texture =
                  textureFor((uint32_t)packet.readbackResourceId);
              if (!texture || readbackRowBytes != readbackWidth * 4 ||
                  !iris_graph_readback_rgba8(texture,
                      (const uint8_t *)readback.contents, readbackLength,
                      readbackWidth, readbackHeight,
                      g_irisMetal4LastGraphFrameRgba8))
                graphReadbackCaptureFailed = true;
            }
            if (!graphReadbackCaptureFailed) {
              status = 1;
              reason = 0;
            } else {
              status = -1;
              reason = 5;
            }
          }
        }
      } @catch (NSException *exception) {
        if (![exception.name isEqualToString:
                @"MetalRenderGraphUnsupported"]) {
          dbg("WARN: Iris graph frame raised %s: %s\n",
              exception.name.UTF8String ?: "NSException",
              exception.reason.UTF8String ?: "unknown reason");
          if (reason == 0)
            reason = 5;
        }
      }
      profileCommitted = CACurrentMediaTime();
      // Every prepared draw retained the frame input objects it references.
      // Drop the table's construction references before normal submission
      // retirement so each allocation has one clear owner thereafter.
      releaseGraphInputs();
      if (presentationQueued && !submissionCommitted) {
        auto queued = std::find_if(g_irisMetal4GraphPresentations.begin(),
            g_irisMetal4GraphPresentations.end(),
            [&](const IrisMetal4GraphPresentation &candidate) {
              return candidate.token == presentationToken;
            });
        if (queued != g_irisMetal4GraphPresentations.end()) {
          if (queued->surface)
            CFRelease(queued->surface);
          g_irisMetal4GraphPresentations.erase(queued);
        }
        presentationQueued = false;
      }
      if (presentationSurface) {
        CFRelease(presentationSurface);
        presentationSurface = nullptr;
      }
      if (presentationTexture && !presentationTextureRetained) {
        [presentationTexture release];
        presentationTexture = nil;
      }
      bool retired = submissionCommitted && iris_graph_retire_submission(
          preparedDraws, retained, frameArgumentArena, encodedObjects,
          readback, residency, allocator, commandBuffer, options,
          submissionState);
      bool safeToRelease = retired || !submissionCommitted ||
          iris_metal4_submission_completed(submissionState);
      if (!safeToRelease) {
        safeToRelease = iris_metal4_wait_for_committed_submission(
            submissionState, 5'000);
        if (!safeToRelease) {
          // Never release objects still referenced by the GPU. This branch is
          // an allocation-failure quarantine, not a steady-state queue: the
          // bounded preflight above makes it unreachable during normal play.
          dbg("ERROR: quarantining an unretired Iris graph submission after "
              "a shared-event timeout\n");
          status = -1;
          reason = 5;
        }
      }
      if (safeToRelease) {
        if (options) [options release];
        if (readback) [readback release];
        if (commandBuffer) [commandBuffer release];
        if (allocator) [allocator release];
        if (residency) {
          [residency endResidency];
          [residency release];
        }
        if (!retired)
          releaseEncodedObjects();
        releasePreparedDraws();
        releaseRetained();
      } else {
        // Raw Objective-C pointers in these containers do not release on
        // vector destruction. Deliberately abandon them so an in-flight GPU
        // submission cannot observe freed resources.
        options = nil;
        readback = nil;
        commandBuffer = nil;
        allocator = nil;
        residency = nil;
        encodedObjects.clear();
        preparedDraws.clear();
        retained.clear();
        frameArgumentArena.abandon();
      }
      CFTimeInterval profileRetired = CACurrentMediaTime();
      if (requirePresentation && status == 1) {
        iris_metal4_record_graph_cpu_profile(profileStarted, profileParsed,
            profileInputsReady, profileDrawsReady,
            profilePresentationReady, profileResidencyCreated,
            profileResidencyPopulated, profileResidencyCommitted,
            profileSetupReady, profileEncoded, profileCommitted,
            profileRetired, profileResidencyRawAllocations,
            profileResidencyUniqueAllocations, profileDrawOperations,
            profileDrawPasses, profileBarrierCalls);
      }
      iris_metal4_finish_input_surface_leases(inputSurfaceLeases,
          submissionCommitted ? submissionState : nullptr);
      return iris_graph_frame_result(env, status,
          status == 1 ? (jlong)packet.operations.size() : 0,
          status == 1 ? clears : 0,
          status == 1 ? transfers : 0,
          status == 1 ? barriers : 0,
          status == 1 ? (jlong)outputHash : 0, reason);
    }
    return iris_graph_frame_result(env, 0, 0, 0, 0, 0, 0, 1);
  }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nRunIrisMetal4GraphFrame(
    JNIEnv *env, jclass, jbyteArray packetValue,
    jint diagnosticReadbackMipLevel) {
  return run_iris_metal4_graph_frame(env, packetValue,
      0, diagnosticReadbackMipLevel, false, false);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSubmitIrisMetal4GraphFrame(
    JNIEnv *env, jclass, jbyteArray packetValue) {
  return run_iris_metal4_graph_frame(env, packetValue, 0, 0, true, false);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSubmitIrisMetal4GraphFrameDirect(
    JNIEnv *env, jclass, jobject packetValue, jint packetLength) {
  return run_iris_metal4_graph_frame(env, packetValue, packetLength, 0,
      true, true);
}

static jlongArray iris_graph_presentation_result(
    JNIEnv *env, jlong status, jlong token, jlong width, jlong height,
    jlong reason) {
  jlong values[] = {status, token, width, height, reason};
  jlongArray result = env->NewLongArray(5);
  if (!result)
    return nullptr;
  env->SetLongArrayRegion(result, 0, 5, values);
  return env->ExceptionCheck() ? nullptr : result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMetal4GraphPresentationStatus(
    JNIEnv *env, jclass, jlong tokenValue) {
  std::unique_lock<std::mutex> executionLock(g_irisMetal4ExecutionMutex,
      std::try_to_lock);
  if (!executionLock.owns_lock()) {
    // The submission worker owns the graph queue while it encodes the next
    // frame. Status polling is speculative: report PENDING without dimensions
    // so the render thread can reuse the last completed IOSurface instead of
    // waiting behind a full native submit.
    return iris_graph_presentation_result(env, 0, tokenValue, 0, 0, 0);
  }
  @autoreleasepool {
    if (tokenValue <= 0)
      return iris_graph_presentation_result(env, -1, 0, 0, 0, 2);
    if (@available(macOS 26.0, *)) {
      uint64_t token = (uint64_t)tokenValue;
      auto found = std::find_if(g_irisMetal4GraphPresentations.begin(),
          g_irisMetal4GraphPresentations.end(),
          [&](const IrisMetal4GraphPresentation &candidate) {
            return candidate.token == token;
          });
      if (found == g_irisMetal4GraphPresentations.end())
        return iris_graph_presentation_result(env, -1, tokenValue, 0, 0, 1);
      bool completed = iris_metal4_submission_completed(
          found->feedbackState);
      if (!completed) {
        return iris_graph_presentation_result(env, 0, tokenValue,
            found->width, found->height, 0);
      }
      bool succeeded = found->feedbackState &&
          found->feedbackState->succeeded.load(std::memory_order_acquire);
      return iris_graph_presentation_result(env, succeeded ? 1 : -1,
          tokenValue, found->width, found->height, succeeded ? 0 : 3);
    }
    return iris_graph_presentation_result(env, -1, tokenValue, 0, 0, 4);
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nPromoteIrisMetal4GraphPresentation(
    JNIEnv *, jclass, jlong tokenValue, jint width, jint height) {
  std::unique_lock<std::mutex> executionLock(g_irisMetal4ExecutionMutex,
      std::try_to_lock);
  if (!executionLock.owns_lock()) {
    // Promotion is retried on the next frame. Keep the completed token in its
    // queue rather than stalling the render thread behind worker submission.
    return JNI_FALSE;
  }
  @autoreleasepool {
    if (tokenValue <= 0 || width <= 0 || height <= 0 ||
        g_irisMetal4PendingCutoverSurface) {
      return JNI_FALSE;
    }
    if (@available(macOS 26.0, *)) {
      uint64_t token = (uint64_t)tokenValue;
      auto found = std::find_if(g_irisMetal4GraphPresentations.begin(),
          g_irisMetal4GraphPresentations.end(),
          [&](const IrisMetal4GraphPresentation &candidate) {
            return candidate.token == token;
          });
      if (found == g_irisMetal4GraphPresentations.end() ||
          found->width != (uint32_t)width ||
          found->height != (uint32_t)height ||
          !iris_metal4_submission_completed(found->feedbackState) ||
          !found->feedbackState ||
          !found->feedbackState->succeeded.load(std::memory_order_acquire) ||
          !found->surface) {
        return JNI_FALSE;
      }
      g_irisMetal4PendingCutoverSurface = found->surface;
      g_irisMetal4PendingCutoverWidth = found->width;
      g_irisMetal4PendingCutoverHeight = found->height;
      found->surface = nullptr;
      g_irisMetal4GraphPresentations.erase(found);
      return JNI_TRUE;
    }
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDiscardIrisMetal4GraphPresentation(
    JNIEnv *, jclass, jlong tokenValue) {
  std::lock_guard<std::mutex> executionLock(g_irisMetal4ExecutionMutex);
  if (tokenValue <= 0)
    return JNI_FALSE;
  uint64_t token = (uint64_t)tokenValue;
  auto found = std::find_if(g_irisMetal4GraphPresentations.begin(),
      g_irisMetal4GraphPresentations.end(),
      [&](const IrisMetal4GraphPresentation &candidate) {
        return candidate.token == token;
      });
  if (found == g_irisMetal4GraphPresentations.end())
    return JNI_FALSE;
  if (found->surface)
    CFRelease(found->surface);
  g_irisMetal4GraphPresentations.erase(found);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMetal4GraphTiming(
    JNIEnv *env, jclass) {
  jlong values[] = {
      (jlong)g_irisMetal4GraphGpuSamples.load(std::memory_order_acquire),
      (jlong)g_irisMetal4GraphLastGpuNs.load(std::memory_order_relaxed),
      (jlong)g_irisMetal4GraphTotalGpuNs.load(std::memory_order_relaxed),
      (jlong)g_irisMetal4GraphMaxGpuNs.load(std::memory_order_relaxed),
      (jlong)g_irisMetal4GraphFeedbackErrors.load(
          std::memory_order_relaxed),
      (jlong)g_irisMetal4GraphCpuSamples.load(std::memory_order_acquire),
      (jlong)g_irisMetal4GraphLastCpuNs.load(std::memory_order_relaxed),
      (jlong)g_irisMetal4GraphTotalCpuNs.load(std::memory_order_relaxed),
      (jlong)g_irisMetal4GraphMaxCpuNs.load(std::memory_order_relaxed),
      (jlong)g_irisMetal4GraphLastQueueNs.load(std::memory_order_relaxed),
      (jlong)g_irisMetal4GraphTotalQueueNs.load(std::memory_order_relaxed),
      (jlong)g_irisMetal4GraphMaxQueueNs.load(std::memory_order_relaxed)};
  jlongArray result = env->NewLongArray(12);
  if (!result)
    return nullptr;
  env->SetLongArrayRegion(result, 0, 12, values);
  return env->ExceptionCheck() ? nullptr : result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMetal4GraphCpuProfile(
    JNIEnv *env, jclass) {
  IrisMetal4GraphCpuProfile profile;
  {
    std::lock_guard<std::mutex> lock(g_irisMetal4GraphCpuProfileMutex);
    profile = g_irisMetal4GraphCpuProfile;
  }
  jlong values[] = {
      (jlong)profile.samples,
      (jlong)profile.parseNs,
      (jlong)profile.inputsNs,
      (jlong)profile.drawsNs,
      (jlong)profile.setupNs,
      (jlong)profile.presentationNs,
      (jlong)profile.residencyCreateNs,
      (jlong)profile.residencyPopulateNs,
      (jlong)profile.residencyCommitNs,
      (jlong)profile.commandNs,
      (jlong)profile.encodeNs,
      (jlong)profile.commitNs,
      (jlong)profile.retireNs,
      (jlong)profile.residencyRawAllocations,
      (jlong)profile.residencyUniqueAllocations,
      (jlong)profile.drawOperations,
      (jlong)profile.drawPasses,
      (jlong)profile.barrierCalls,
  };
  jlongArray result = env->NewLongArray(18);
  if (!result)
    return nullptr;
  env->SetLongArrayRegion(result, 0, 18, values);
  return env->ExceptionCheck() ? nullptr : result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nResetIrisMetal4GraphPerformanceSamples(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(g_irisMetal4GraphPerformanceMutex);
  g_irisMetal4GraphPerformanceSampling.store(false,
                                              std::memory_order_release);
  g_irisMetal4GraphPerformanceSamples.clear();
  g_irisMetal4GraphPerformanceDropped = 0;
  try {
    g_irisMetal4GraphPerformanceSamples.reserve(
        kIrisMetal4GraphPerformanceSampleLimit);
  } catch (...) {
    return JNI_FALSE;
  }
  g_irisMetal4GraphPerformanceSampling.store(true,
                                              std::memory_order_release);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrainIrisMetal4GraphPerformanceSamples(
    JNIEnv *env, jclass) {
  std::vector<uint64_t> samples;
  uint64_t dropped = 0;
  {
    std::lock_guard<std::mutex> lock(g_irisMetal4GraphPerformanceMutex);
    samples.swap(g_irisMetal4GraphPerformanceSamples);
    dropped = g_irisMetal4GraphPerformanceDropped;
    g_irisMetal4GraphPerformanceDropped = 0;
  }
  if (samples.size() >= (size_t)std::numeric_limits<jsize>::max())
    return nullptr;
  jsize length = (jsize)samples.size() + 1;
  jlongArray result = env->NewLongArray(length);
  if (!result)
    return nullptr;
  jlong droppedValue = (jlong)dropped;
  env->SetLongArrayRegion(result, 0, 1, &droppedValue);
  if (env->ExceptionCheck() || samples.empty())
    return env->ExceptionCheck() ? nullptr : result;
  std::vector<jlong> javaSamples;
  try {
    javaSamples.reserve(samples.size());
    for (uint64_t sample : samples)
      javaSamples.push_back((jlong)sample);
  } catch (...) {
    return nullptr;
  }
  env->SetLongArrayRegion(result, 1, (jsize)javaSamples.size(),
                          javaSamples.data());
  return env->ExceptionCheck() ? nullptr : result;
}

static MTLVertexFormat iris_metal_vertex_format(const std::string &name) {
#define IRIS_VERTEX(n, value)                                                  \
  if (name == n)                                                              \
    return value
  IRIS_VERTEX("r8-unorm", MTLVertexFormatUCharNormalized);
  IRIS_VERTEX("rg8-unorm", MTLVertexFormatUChar2Normalized);
  IRIS_VERTEX("rgb8-unorm", MTLVertexFormatUChar3Normalized);
  IRIS_VERTEX("rgba8-unorm", MTLVertexFormatUChar4Normalized);
  IRIS_VERTEX("r8-snorm", MTLVertexFormatCharNormalized);
  IRIS_VERTEX("rg8-snorm", MTLVertexFormatChar2Normalized);
  IRIS_VERTEX("rgb8-snorm", MTLVertexFormatChar3Normalized);
  IRIS_VERTEX("rgba8-snorm", MTLVertexFormatChar4Normalized);
  IRIS_VERTEX("r8-uint", MTLVertexFormatUChar);
  IRIS_VERTEX("rg8-uint", MTLVertexFormatUChar2);
  IRIS_VERTEX("rgb8-uint", MTLVertexFormatUChar3);
  IRIS_VERTEX("rgba8-uint", MTLVertexFormatUChar4);
  IRIS_VERTEX("r8-sint", MTLVertexFormatChar);
  IRIS_VERTEX("rg8-sint", MTLVertexFormatChar2);
  IRIS_VERTEX("rgb8-sint", MTLVertexFormatChar3);
  IRIS_VERTEX("rgba8-sint", MTLVertexFormatChar4);
  IRIS_VERTEX("r16-unorm", MTLVertexFormatUShortNormalized);
  IRIS_VERTEX("rg16-unorm", MTLVertexFormatUShort2Normalized);
  IRIS_VERTEX("rgb16-unorm", MTLVertexFormatUShort3Normalized);
  IRIS_VERTEX("rgba16-unorm", MTLVertexFormatUShort4Normalized);
  IRIS_VERTEX("r16-snorm", MTLVertexFormatShortNormalized);
  IRIS_VERTEX("rg16-snorm", MTLVertexFormatShort2Normalized);
  IRIS_VERTEX("rgb16-snorm", MTLVertexFormatShort3Normalized);
  IRIS_VERTEX("rgba16-snorm", MTLVertexFormatShort4Normalized);
  IRIS_VERTEX("r16-uint", MTLVertexFormatUShort);
  IRIS_VERTEX("rg16-uint", MTLVertexFormatUShort2);
  IRIS_VERTEX("rgb16-uint", MTLVertexFormatUShort3);
  IRIS_VERTEX("rgba16-uint", MTLVertexFormatUShort4);
  IRIS_VERTEX("r16-sint", MTLVertexFormatShort);
  IRIS_VERTEX("rg16-sint", MTLVertexFormatShort2);
  IRIS_VERTEX("rgb16-sint", MTLVertexFormatShort3);
  IRIS_VERTEX("rgba16-sint", MTLVertexFormatShort4);
  IRIS_VERTEX("r16-float", MTLVertexFormatHalf);
  IRIS_VERTEX("rg16-float", MTLVertexFormatHalf2);
  IRIS_VERTEX("rgb16-float", MTLVertexFormatHalf3);
  IRIS_VERTEX("rgba16-float", MTLVertexFormatHalf4);
  IRIS_VERTEX("r32-float", MTLVertexFormatFloat);
  IRIS_VERTEX("rg32-float", MTLVertexFormatFloat2);
  IRIS_VERTEX("rgb32-float", MTLVertexFormatFloat3);
  IRIS_VERTEX("rgba32-float", MTLVertexFormatFloat4);
  IRIS_VERTEX("r32-sint", MTLVertexFormatInt);
  IRIS_VERTEX("rg32-sint", MTLVertexFormatInt2);
  IRIS_VERTEX("rgb32-sint", MTLVertexFormatInt3);
  IRIS_VERTEX("rgba32-sint", MTLVertexFormatInt4);
  IRIS_VERTEX("r32-uint", MTLVertexFormatUInt);
  IRIS_VERTEX("rg32-uint", MTLVertexFormatUInt2);
  IRIS_VERTEX("rgb32-uint", MTLVertexFormatUInt3);
  IRIS_VERTEX("rgba32-uint", MTLVertexFormatUInt4);
  IRIS_VERTEX("rgb10a2-unorm", MTLVertexFormatUInt1010102Normalized);
  IRIS_VERTEX("rg11b10-float", MTLVertexFormatFloatRG11B10);
  IRIS_VERTEX("rgb9e5-float", MTLVertexFormatFloatRGB9E5);
#undef IRIS_VERTEX
  return MTLVertexFormatInvalid;
}

static MTLBlendFactor iris_metal_blend_factor(uint32_t value) {
  switch (value) {
  case 0: return MTLBlendFactorZero;
  case 1: return MTLBlendFactorOne;
  case 2: return MTLBlendFactorSourceColor;
  case 3: return MTLBlendFactorOneMinusSourceColor;
  case 4: return MTLBlendFactorDestinationColor;
  case 5: return MTLBlendFactorOneMinusDestinationColor;
  case 6: return MTLBlendFactorSourceAlpha;
  case 7: return MTLBlendFactorOneMinusSourceAlpha;
  case 8: return MTLBlendFactorDestinationAlpha;
  case 9: return MTLBlendFactorOneMinusDestinationAlpha;
  case 10: return MTLBlendFactorBlendColor;
  case 11: return MTLBlendFactorOneMinusBlendColor;
  case 12: return MTLBlendFactorBlendAlpha;
  case 13: return MTLBlendFactorOneMinusBlendAlpha;
  case 14: return MTLBlendFactorSourceAlphaSaturated;
  case 15: return MTLBlendFactorSource1Color;
  case 16: return MTLBlendFactorOneMinusSource1Color;
  case 17: return MTLBlendFactorSource1Alpha;
  case 18: return MTLBlendFactorOneMinusSource1Alpha;
  default: return MTLBlendFactorZero;
  }
}

static MTLCompareFunction iris_metal_compare(uint32_t value) {
  static const MTLCompareFunction values[] = {
      MTLCompareFunctionNever, MTLCompareFunctionLess,
      MTLCompareFunctionEqual, MTLCompareFunctionLessEqual,
      MTLCompareFunctionGreater, MTLCompareFunctionNotEqual,
      MTLCompareFunctionGreaterEqual, MTLCompareFunctionAlways};
  return value < 8 ? values[value] : MTLCompareFunctionAlways;
}

static MTLStencilOperation iris_metal_stencil(uint32_t value) {
  static const MTLStencilOperation values[] = {
      MTLStencilOperationKeep, MTLStencilOperationZero,
      MTLStencilOperationReplace, MTLStencilOperationIncrementClamp,
      MTLStencilOperationDecrementClamp, MTLStencilOperationInvert,
      MTLStencilOperationIncrementWrap, MTLStencilOperationDecrementWrap};
  return value < 8 ? values[value] : MTLStencilOperationKeep;
}

static MTLPrimitiveTopologyClass iris_metal_topology_class(uint32_t value) {
  if (value == 0)
    return MTLPrimitiveTopologyClassPoint;
  if (value >= 1 && value <= 3)
    return MTLPrimitiveTopologyClassLine;
  if (value >= 4 && value <= 6)
    return MTLPrimitiveTopologyClassTriangle;
  return MTLPrimitiveTopologyClassUnspecified;
}

static bool iris_metal_primitive_type(uint32_t value,
                                      MTLPrimitiveType &result) {
  switch (value) {
  case 0: result = MTLPrimitiveTypePoint; return true;
  case 1: result = MTLPrimitiveTypeLine; return true;
  // OpenGL line loops are expanded to a closed line strip in the immutable
  // draw snapshot before this pipeline is used.
  case 2: result = MTLPrimitiveTypeLineStrip; return true;
  case 3: result = MTLPrimitiveTypeLineStrip; return true;
  case 4: result = MTLPrimitiveTypeTriangle; return true;
  case 5: result = MTLPrimitiveTypeTriangleStrip; return true;
  // OpenGL triangle fans are expanded to an explicit triangle list.
  case 6: result = MTLPrimitiveTypeTriangle; return true;
  default: return false;
  }
}

static uint32_t iris_metal_effective_packet_topology(uint32_t value) {
  if (value == 2)
    return 3;
  if (value == 6)
    return 4;
  return value;
}

static bool iris_metal4_execution_state(
    const IrisParsedPipelineDescriptor &parsed,
    IrisMetal4PipelineEntry &entry) {
  switch (parsed.cullMode) {
  case 0: entry.cullMode = MTLCullModeNone; break;
  case 1: entry.cullMode = MTLCullModeFront; break;
  case 2: entry.cullMode = MTLCullModeBack; break;
  default: return false;
  }
  // The fixed Iris translation profile asks SPIRV-Cross to flip vertex Y.
  // That reflection reverses triangle winding, so compensate here to retain
  // the original OpenGL front/back and culling semantics.
  entry.frontFacingWinding = parsed.frontFace == 0
      ? MTLWindingCounterClockwise : MTLWindingClockwise;
  if (parsed.frontFill != parsed.backFill || parsed.frontFill > 1)
    return false;
  entry.triangleFillMode = parsed.frontFill == 0
      ? MTLTriangleFillModeFill : MTLTriangleFillModeLines;
  entry.depthClipMode = parsed.depthClip == 0
      ? MTLDepthClipModeClip : MTLDepthClipModeClamp;
  entry.sampleMask = (NSUInteger)parsed.sampleMask;
  std::memcpy(&entry.depthBias, &parsed.depthBiasBits, sizeof(float));
  std::memcpy(&entry.slopeScale, &parsed.slopeScaleBits, sizeof(float));
  std::memcpy(&entry.depthBiasClamp, &parsed.depthBiasClampBits,
              sizeof(float));
  if (!std::isfinite(entry.depthBias) || !std::isfinite(entry.slopeScale) ||
      !std::isfinite(entry.depthBiasClamp) || parsed.sampleCoverageEnabled)
    return false;
  entry.polygonOffsetMask = parsed.polygonOffsetMask;
  entry.frontStencilReference = parsed.stencilFront.reference;
  entry.backStencilReference = parsed.stencilBack.reference;
  entry.topology = parsed.topology;
  entry.restartMode = parsed.restartMode;
  entry.patchControlPoints = parsed.patchControlPoints;
  return parsed.topology != 7;
}

static MTLColorWriteMask iris_metal_write_mask(uint32_t mask) {
  MTLColorWriteMask result = MTLColorWriteMaskNone;
  if (mask & 1) result |= MTLColorWriteMaskRed;
  if (mask & 2) result |= MTLColorWriteMaskGreen;
  if (mask & 4) result |= MTLColorWriteMaskBlue;
  if (mask & 8) result |= MTLColorWriteMaskAlpha;
  return result;
}

static bool copy_bounded_jbyte_array(JNIEnv *env, jbyteArray input,
                                     std::vector<jbyte> &output,
                                     bool required) {
  if (!input)
    return !required;
  jsize length = env->GetArrayLength(input);
  if (length <= 0 || length > kIrisMslMaximumSourceBytes)
    return false;
  try {
    output.resize((size_t)length);
  } catch (...) {
    return false;
  }
  env->GetByteArrayRegion(input, 0, length, output.data());
  return !env->ExceptionCheck();
}

static id<MTLLibrary> iris_metal4_compile_library(
    const std::vector<jbyte> &sourceBytes, NSString *name, NSError **error) {
  if (@available(macOS 26.0, *)) {
    if (!g_irisMetal4Compiler || sourceBytes.empty())
      return nil;
    NSString *source = [[NSString alloc]
        initWithBytes:sourceBytes.data()
               length:sourceBytes.size()
             encoding:NSUTF8StringEncoding];
    if (!source)
      return nil;
    MTLCompileOptions *options = [[MTLCompileOptions alloc] init];
    options.languageVersion = MTLLanguageVersion3_0;
    options.libraryType = MTLLibraryTypeExecutable;
    options.preserveInvariance = YES;
    options.mathMode = MTLMathModeFast;
    MTL4LibraryDescriptor *descriptor =
        [[MTL4LibraryDescriptor alloc] init];
    descriptor.source = source;
    descriptor.options = options;
    descriptor.name = name;
    id<MTLLibrary> library =
        [(id<MTL4Compiler>)g_irisMetal4Compiler
            newLibraryWithDescriptor:descriptor error:error];
    [descriptor release];
    [options release];
    [source release];
    return library;
  }
  return nil;
}

// Called under g_irisMetal4PipelineCacheMutex. The map owns one reference and
// the returned value owns another, keeping caller cleanup uniform.
static id<MTLLibrary> iris_metal4_cached_library(
    const std::string &cacheKey, const std::vector<jbyte> &sourceBytes,
    NSString *name, NSError **error) {
  auto existing = g_irisMetal4Libraries.find(cacheKey);
  if (existing != g_irisMetal4Libraries.end())
    return [existing->second retain];
  id<MTLLibrary> library =
      iris_metal4_compile_library(sourceBytes, name, error);
  if (!library)
    return nil;
  g_irisMetal4Libraries.emplace(cacheKey, library);
  return [library retain];
}

static bool set_iris_function_constant(MTLFunctionConstantValues *values,
                                       const IrisPipelineFunctionConstant &c) {
  switch (c.type) {
  case 0: {
    bool value = c.bits != 0;
    [values setConstantValue:&value type:MTLDataTypeBool atIndex:c.index];
    return true;
  }
  case 1: {
    int32_t value = (int32_t)c.bits;
    [values setConstantValue:&value type:MTLDataTypeInt atIndex:c.index];
    return true;
  }
  case 2: {
    uint32_t value = (uint32_t)c.bits;
    [values setConstantValue:&value type:MTLDataTypeUInt atIndex:c.index];
    return true;
  }
  case 3: {
    uint32_t bits = (uint32_t)c.bits;
    float value;
    std::memcpy(&value, &bits, sizeof(value));
    [values setConstantValue:&value type:MTLDataTypeFloat atIndex:c.index];
    return true;
  }
  case 4: {
    int64_t value = (int64_t)c.bits;
    [values setConstantValue:&value type:MTLDataTypeLong atIndex:c.index];
    return true;
  }
  case 5: {
    uint64_t value = c.bits;
    [values setConstantValue:&value type:MTLDataTypeULong atIndex:c.index];
    return true;
  }
  default:
    // MSL has no double-precision scalar function constant.
    return false;
  }
}

static MTL4FunctionDescriptor *iris_metal4_function_descriptor(
    id<MTLLibrary> library, uint32_t stage,
    const std::vector<IrisPipelineFunctionConstant> &constants,
    bool *supported) API_AVAILABLE(macos(26.0)) {
  if (@available(macOS 26.0, *)) {
    MTL4LibraryFunctionDescriptor *base =
        [[MTL4LibraryFunctionDescriptor alloc] init];
    base.name = @"main0";
    base.library = library;
    bool hasConstants = false;
    for (const auto &constant : constants)
      hasConstants |= constant.stage == stage;
    if (!hasConstants)
      return base;

    MTLFunctionConstantValues *values =
        [[MTLFunctionConstantValues alloc] init];
    for (const auto &constant : constants) {
      if (constant.stage == stage &&
          !set_iris_function_constant(values, constant)) {
        *supported = false;
        [values release];
        [base release];
        return nil;
      }
    }
    MTL4SpecializedFunctionDescriptor *specialized =
        [[MTL4SpecializedFunctionDescriptor alloc] init];
    specialized.functionDescriptor = base;
    specialized.constantValues = values;
    [values release];
    [base release];
    return specialized;
  }
  *supported = false;
  return nil;
}

static id<MTLFunction> iris_metal_runtime_function(
    id<MTLLibrary> library, uint32_t stage,
    const std::vector<IrisPipelineFunctionConstant> &constants,
    NSError **error, bool *supported) {
  bool hasConstants = false;
  for (const auto &constant : constants)
    hasConstants |= constant.stage == stage;
  if (!hasConstants)
    return [library newFunctionWithName:@"main0"];
  MTLFunctionConstantValues *values =
      [[MTLFunctionConstantValues alloc] init];
  for (const auto &constant : constants) {
    if (constant.stage == stage &&
        !set_iris_function_constant(values, constant)) {
      *supported = false;
      [values release];
      return nil;
    }
  }
  id<MTLFunction> function = [library newFunctionWithName:@"main0"
                                          constantValues:values
                                                   error:error];
  [values release];
  return function;
}

static id<MTLDepthStencilState> iris_metal_depth_stencil_state(
    const IrisParsedPipelineDescriptor &parsed) {
  MTLDepthStencilDescriptor *descriptor =
      [[MTLDepthStencilDescriptor alloc] init];
  descriptor.depthCompareFunction = parsed.depthTest
      ? iris_metal_compare(parsed.depthCompare) : MTLCompareFunctionAlways;
  descriptor.depthWriteEnabled = parsed.depthWrite;
  if (parsed.stencilEnabled) {
    auto makeFace = [](const IrisPipelineStencilFace &face) {
      MTLStencilDescriptor *result = [[MTLStencilDescriptor alloc] init];
      result.stencilCompareFunction = iris_metal_compare(face.compare);
      result.stencilFailureOperation = iris_metal_stencil(face.stencilFail);
      result.depthFailureOperation = iris_metal_stencil(face.depthFail);
      result.depthStencilPassOperation = iris_metal_stencil(face.pass);
      result.readMask = face.readMask;
      result.writeMask = face.writeMask;
      return result;
    };
    MTLStencilDescriptor *front = makeFace(parsed.stencilFront);
    MTLStencilDescriptor *back = makeFace(parsed.stencilBack);
    descriptor.frontFaceStencil = front;
    descriptor.backFaceStencil = back;
    [front release];
    [back release];
  }
  id<MTLDepthStencilState> result =
      [g_device newDepthStencilStateWithDescriptor:descriptor];
  [descriptor release];
  return result;
}

static MTLVertexDescriptor *iris_metal_vertex_descriptor(
    const IrisParsedPipelineDescriptor &parsed, bool *supported) {
  MTLVertexDescriptor *descriptor = [[MTLVertexDescriptor alloc] init];
  for (const auto &buffer : parsed.buffers) {
    MTLVertexBufferLayoutDescriptor *layout =
        descriptor.layouts[buffer.index];
    layout.stride = buffer.stride;
    layout.stepFunction = buffer.stepFunction == 0
        ? MTLVertexStepFunctionPerVertex
        : buffer.stepFunction == 1 ? MTLVertexStepFunctionPerInstance
                                   : MTLVertexStepFunctionConstant;
    layout.stepRate = buffer.stepFunction == 1 ? buffer.stepRate
                                                : buffer.stepFunction == 0;
  }
  for (const auto &attribute : parsed.attributes) {
    MTLVertexFormat format = iris_metal_vertex_format(attribute.format);
    if (format == MTLVertexFormatInvalid) {
      *supported = false;
      [descriptor release];
      return nil;
    }
    MTLVertexAttributeDescriptor *target =
        descriptor.attributes[attribute.location];
    target.format = format;
    target.offset = attribute.offset;
    target.bufferIndex = attribute.buffer;
  }
  return descriptor;
}

static bool valid_iris_pipeline_key(const char *key) {
  if (!key || std::strlen(key) != 64)
    return false;
  for (size_t index = 0; index < 64; ++index) {
    char value = key[index];
    if (!((value >= '0' && value <= '9') ||
          (value >= 'a' && value <= 'f')))
      return false;
  }
  return true;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMetal4PipelineCacheIdentity(
    JNIEnv *env, jclass) {
  @autoreleasepool {
    if (!g_device ||
        !g_metal4RuntimeVerified.load(std::memory_order_acquire))
      return env->NewStringUTF("");
    if (@available(macOS 26.0, *)) {
      NSBundle *metalBundle = [NSBundle bundleWithPath:
          @"/System/Library/Frameworks/Metal.framework"];
      NSString *frameworkVersion =
          [metalBundle objectForInfoDictionaryKey:@"CFBundleVersion"];
      if (!frameworkVersion)
        frameworkVersion = @"unknown";
      NSString *identity = [NSString stringWithFormat:
          @"device=%@;registry=%llu;os=%@;metal-framework=%@;"
           "argument-buffers=%lu;msl=3.0;mtl4=1;math=fast;invariance=1",
          [g_device name], (unsigned long long)[g_device registryID],
          [[NSProcessInfo processInfo] operatingSystemVersionString],
          frameworkVersion, (unsigned long)[g_device argumentBuffersSupport]];
      return env->NewStringUTF([identity UTF8String]);
    }
    return env->NewStringUTF("");
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nConfigureIrisMetal4PipelineCache(
    JNIEnv *env, jclass, jstring archivePathValue) {
  @autoreleasepool {
    if (!archivePathValue)
      return kIrisMetal4PipelineFailed;
    const char *utf8 = env->GetStringUTFChars(archivePathValue, nullptr);
    if (!utf8)
      return kIrisMetal4PipelineFailed;
    size_t length = std::strlen(utf8);
    NSString *path = length > 0 && length <= 4096
        ? [NSString stringWithUTF8String:utf8] : nil;
    env->ReleaseStringUTFChars(archivePathValue, utf8);
    if (!path || ![path isAbsolutePath])
      return kIrisMetal4PipelineFailed;
    std::lock_guard<std::mutex> lock(g_irisMetal4PipelineCacheMutex);
    return prepare_iris_metal4_pipeline_cache_for_translated_msl_locked(path);
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nCompileIrisMetal4Pipeline(
    JNIEnv *env, jclass, jstring pipelineKeyValue, jstring shaderKeyValue,
    jbyteArray descriptorValue, jbyteArray vertexValue,
    jbyteArray fragmentValue, jbyteArray computeValue) {
  @autoreleasepool {
    if (!g_device ||
        !g_metal4RuntimeVerified.load(std::memory_order_acquire))
      return kIrisMetal4PipelineDeferred;
    if (@available(macOS 26.0, *)) {
      if (!pipelineKeyValue || !shaderKeyValue || !descriptorValue)
        return kIrisMetal4PipelineFailed;
      const char *pipelineKey =
          env->GetStringUTFChars(pipelineKeyValue, nullptr);
      if (!pipelineKey)
        return kIrisMetal4PipelineFailed;
      std::string key(pipelineKey);
      bool validKey = valid_iris_pipeline_key(pipelineKey);
      env->ReleaseStringUTFChars(pipelineKeyValue, pipelineKey);
      if (!validKey)
        return kIrisMetal4PipelineFailed;
      const char *shaderKeyUtf8 =
          env->GetStringUTFChars(shaderKeyValue, nullptr);
      if (!shaderKeyUtf8)
        return kIrisMetal4PipelineFailed;
      std::string shaderKey(shaderKeyUtf8);
      bool validShaderKey = valid_iris_pipeline_key(shaderKeyUtf8);
      env->ReleaseStringUTFChars(shaderKeyValue, shaderKeyUtf8);
      if (!validShaderKey)
        return kIrisMetal4PipelineFailed;

      jsize descriptorLength = env->GetArrayLength(descriptorValue);
      if (descriptorLength <= 0 ||
          descriptorLength > kIrisMetal4MaximumDescriptorBytes)
        return kIrisMetal4PipelineFailed;
      std::vector<jbyte> descriptorBytes;
      try {
        descriptorBytes.resize((size_t)descriptorLength);
      } catch (...) {
        return kIrisMetal4PipelineFailed;
      }
      env->GetByteArrayRegion(descriptorValue, 0, descriptorLength,
                              descriptorBytes.data());
      if (env->ExceptionCheck())
        return kIrisMetal4PipelineFailed;
      IrisParsedPipelineDescriptor parsed;
      if (!parse_iris_metal4_pipeline_descriptor(descriptorBytes, parsed))
        return kIrisMetal4PipelineFailed;

      std::lock_guard<std::mutex> lock(g_irisMetal4PipelineCacheMutex);
      if (!g_irisMetal4Compiler || !g_irisMetal4PipelineSerializer)
        return kIrisMetal4PipelineDeferred;
      auto existing = g_irisMetal4Pipelines.find(key);
      if (existing != g_irisMetal4Pipelines.end())
        return kIrisMetal4PipelineCacheHit;
      g_irisMetal4PipelineAttemptCount.fetch_add(1,
                                                  std::memory_order_relaxed);

      bool compute = parsed.passKind == 2;
      std::vector<jbyte> vertexBytes;
      std::vector<jbyte> fragmentBytes;
      std::vector<jbyte> computeBytes;
      if (!copy_bounded_jbyte_array(env, vertexValue, vertexBytes, !compute) ||
          !copy_bounded_jbyte_array(env, fragmentValue, fragmentBytes,
                                    !compute && parsed.rasterizationEnabled) ||
          !copy_bounded_jbyte_array(env, computeValue, computeBytes, compute)) {
        g_irisMetal4PipelineFailureCount.fetch_add(
            1, std::memory_order_relaxed);
        return kIrisMetal4PipelineFailed;
      }

      NSError *error = nil;
      bool supported = true;
      IrisMetal4PipelineEntry entry;
      if (!compute && !iris_metal4_execution_state(parsed, entry))
        return kIrisMetal4PipelineUnsupported;
      if (compute) {
        id<MTLLibrary> library = iris_metal4_cached_library(
            shaderKey + ":compute", computeBytes,
            @"MetalRender Iris compute", &error);
        if (!library) {
          pipeline_archive_warning("Iris MTL4 compute library failed", error);
          g_irisMetal4PipelineFailureCount.fetch_add(
              1, std::memory_order_relaxed);
          return kIrisMetal4PipelineFailed;
        }
        MTL4FunctionDescriptor *function = iris_metal4_function_descriptor(
            library, 5, parsed.constants, &supported);
        id<MTLFunction> runtimeFunction = iris_metal_runtime_function(
            library, 5, parsed.constants, &error, &supported);
        MTL4ComputePipelineDescriptor *pipelineDescriptor =
            [[MTL4ComputePipelineDescriptor alloc] init];
        pipelineDescriptor.label = [NSString stringWithUTF8String:key.c_str()];
        pipelineDescriptor.computeFunctionDescriptor = function;
        id<MTLComputePipelineState> pipeline = nil;
        bool archiveHit = false;
        if (supported && g_irisMetal4LookupArchives.count > 0) {
          for (id archive in g_irisMetal4LookupArchives) {
            NSError *lookupError = nil;
            pipeline = [(id<MTL4Archive>)archive
                newComputePipelineStateWithDescriptor:pipelineDescriptor
                                                 error:&lookupError];
            if (pipeline)
              break;
          }
          archiveHit = pipeline != nil;
        }
        if (supported && !pipeline) {
          MTL4CompilerTaskOptions *taskOptions =
              [[MTL4CompilerTaskOptions alloc] init];
          if (g_irisMetal4LookupArchives.count > 0)
            taskOptions.lookupArchives = g_irisMetal4LookupArchives;
          pipeline = [(id<MTL4Compiler>)g_irisMetal4Compiler
              newComputePipelineStateWithDescriptor:pipelineDescriptor
                                 compilerTaskOptions:taskOptions
                                               error:&error];
          [taskOptions release];
        }
        id archiveDescriptor = supported && pipeline
            ? [pipelineDescriptor copy] : nil;
        [pipelineDescriptor release];
        if (function) [function release];
        [library release];
        if (!supported) {
          if (archiveDescriptor) [archiveDescriptor release];
          if (runtimeFunction) [runtimeFunction release];
          return kIrisMetal4PipelineUnsupported;
        }
        if (!pipeline || !runtimeFunction) {
          pipeline_archive_warning("Iris MTL4 compute pipeline failed", error);
          if (pipeline) [pipeline release];
          if (runtimeFunction) [runtimeFunction release];
          if (archiveDescriptor) [archiveDescriptor release];
          g_irisMetal4PipelineFailureCount.fetch_add(
              1, std::memory_order_relaxed);
          return kIrisMetal4PipelineFailed;
        }
        if (!g_irisMetal4ComputeArchiveDescriptors) {
          g_irisMetal4ComputeArchiveDescriptors =
              [[NSMutableArray alloc] init];
        }
        [g_irisMetal4ComputeArchiveDescriptors addObject:archiveDescriptor];
        [archiveDescriptor release];
        entry.compute = pipeline;
        entry.computeFunction = runtimeFunction;
        g_irisMetal4Pipelines.emplace(key, entry);
        if (archiveHit) {
          g_irisMetal4PipelineCacheHitCount.fetch_add(
              1, std::memory_order_relaxed);
          return kIrisMetal4PipelineCacheHit;
        }
      } else {
        id<MTLLibrary> vertexLibrary = iris_metal4_cached_library(
            shaderKey + ":vertex", vertexBytes,
            @"MetalRender Iris vertex", &error);
        if (!vertexLibrary) {
          pipeline_archive_warning("Iris MTL4 vertex library failed", error);
          g_irisMetal4PipelineFailureCount.fetch_add(
              1, std::memory_order_relaxed);
          return kIrisMetal4PipelineFailed;
        }
        id<MTLLibrary> fragmentLibrary = nil;
        if (!fragmentBytes.empty()) {
          fragmentLibrary = iris_metal4_cached_library(
              shaderKey + ":fragment", fragmentBytes,
              @"MetalRender Iris fragment", &error);
        }
        if (!fragmentBytes.empty() && !fragmentLibrary) {
          [vertexLibrary release];
          pipeline_archive_warning("Iris MTL4 fragment library failed", error);
          g_irisMetal4PipelineFailureCount.fetch_add(
              1, std::memory_order_relaxed);
          return kIrisMetal4PipelineFailed;
        }
        MTL4FunctionDescriptor *vertexFunction =
            iris_metal4_function_descriptor(vertexLibrary, 0,
                                             parsed.constants, &supported);
        MTL4FunctionDescriptor *fragmentFunction = fragmentLibrary
            ? iris_metal4_function_descriptor(fragmentLibrary, 4,
                                               parsed.constants, &supported)
            : nil;
        id<MTLFunction> runtimeVertex = iris_metal_runtime_function(
            vertexLibrary, 0, parsed.constants, &error, &supported);
        id<MTLFunction> runtimeFragment = fragmentLibrary
            ? iris_metal_runtime_function(fragmentLibrary, 4,
                                          parsed.constants, &error,
                                          &supported)
            : nil;
        MTLVertexDescriptor *vertexDescriptor =
            iris_metal_vertex_descriptor(parsed, &supported);
        MTL4RenderPipelineDescriptor *pipelineDescriptor =
            [[MTL4RenderPipelineDescriptor alloc] init];
        pipelineDescriptor.label = [NSString stringWithUTF8String:key.c_str()];
        pipelineDescriptor.vertexFunctionDescriptor = vertexFunction;
        pipelineDescriptor.fragmentFunctionDescriptor = fragmentFunction;
        pipelineDescriptor.vertexDescriptor = vertexDescriptor;
        pipelineDescriptor.rasterSampleCount = parsed.rasterSampleCount;
        pipelineDescriptor.alphaToCoverageState = parsed.alphaToCoverage
            ? MTL4AlphaToCoverageStateEnabled
            : MTL4AlphaToCoverageStateDisabled;
        pipelineDescriptor.alphaToOneState = parsed.alphaToOne
            ? MTL4AlphaToOneStateEnabled : MTL4AlphaToOneStateDisabled;
        pipelineDescriptor.rasterizationEnabled = parsed.rasterizationEnabled;
        pipelineDescriptor.inputPrimitiveTopology =
            iris_metal_topology_class(parsed.topology);
        for (const auto &color : parsed.colors) {
          MTLPixelFormat format = iris_metal_pixel_format(color.format);
          if (format == MTLPixelFormatInvalid) {
            supported = false;
            break;
          }
          MTL4RenderPipelineColorAttachmentDescriptor *target =
              pipelineDescriptor.colorAttachments[color.slot];
          target.pixelFormat = format;
          target.writeMask = iris_metal_write_mask(color.writeMask);
          target.blendingState = color.blendEnabled
              ? MTL4BlendStateEnabled : MTL4BlendStateDisabled;
          if (color.blendEnabled) {
            target.rgbBlendOperation =
                (MTLBlendOperation)color.rgb.operation;
            target.sourceRGBBlendFactor =
                iris_metal_blend_factor(color.rgb.source);
            target.destinationRGBBlendFactor =
                iris_metal_blend_factor(color.rgb.destination);
            target.alphaBlendOperation =
                (MTLBlendOperation)color.alpha.operation;
            target.sourceAlphaBlendFactor =
                iris_metal_blend_factor(color.alpha.source);
            target.destinationAlphaBlendFactor =
                iris_metal_blend_factor(color.alpha.destination);
          }
        }
        id<MTLRenderPipelineState> pipeline = nil;
        bool archiveHit = false;
        if (supported && g_irisMetal4LookupArchives.count > 0) {
          for (id archive in g_irisMetal4LookupArchives) {
            NSError *lookupError = nil;
            pipeline = [(id<MTL4Archive>)archive
                newRenderPipelineStateWithDescriptor:pipelineDescriptor
                                                error:&lookupError];
            if (pipeline)
              break;
          }
          archiveHit = pipeline != nil;
        }
        if (supported && !pipeline) {
          MTL4CompilerTaskOptions *taskOptions =
              [[MTL4CompilerTaskOptions alloc] init];
          if (g_irisMetal4LookupArchives.count > 0)
            taskOptions.lookupArchives = g_irisMetal4LookupArchives;
          pipeline = [(id<MTL4Compiler>)g_irisMetal4Compiler
              newRenderPipelineStateWithDescriptor:pipelineDescriptor
                                compilerTaskOptions:taskOptions
                                              error:&error];
          [taskOptions release];
        }
        id archiveDescriptor = supported && pipeline
            ? [pipelineDescriptor copy] : nil;
        [pipelineDescriptor release];
        if (vertexDescriptor) [vertexDescriptor release];
        if (vertexFunction) [vertexFunction release];
        if (fragmentFunction) [fragmentFunction release];
        [vertexLibrary release];
        if (fragmentLibrary) [fragmentLibrary release];
        if (!supported) {
          if (archiveDescriptor) [archiveDescriptor release];
          if (runtimeVertex) [runtimeVertex release];
          if (runtimeFragment) [runtimeFragment release];
          return kIrisMetal4PipelineUnsupported;
        }
        if (!pipeline || !runtimeVertex ||
            (fragmentLibrary && !runtimeFragment)) {
          pipeline_archive_warning("Iris MTL4 render pipeline failed", error);
          if (pipeline) [pipeline release];
          if (runtimeVertex) [runtimeVertex release];
          if (runtimeFragment) [runtimeFragment release];
          if (archiveDescriptor) [archiveDescriptor release];
          g_irisMetal4PipelineFailureCount.fetch_add(
              1, std::memory_order_relaxed);
          return kIrisMetal4PipelineFailed;
        }
        entry.render = pipeline;
        entry.vertexFunction = runtimeVertex;
        entry.fragmentFunction = runtimeFragment;
        entry.rasterSampleCount = parsed.rasterSampleCount;
        size_t colorSlotCount = 0;
        for (const auto &color : parsed.colors)
          colorSlotCount = std::max(colorSlotCount, (size_t)color.slot + 1u);
        entry.colorFormats.resize(colorSlotCount, MTLPixelFormatInvalid);
        for (const auto &color : parsed.colors)
          entry.colorFormats[color.slot] =
              iris_metal_pixel_format(color.format);
        entry.depthFormat = parsed.hasDepthFormat
            ? iris_metal_pixel_format(parsed.depthFormat)
            : MTLPixelFormatInvalid;
        entry.stencilFormat = parsed.hasStencilFormat
            ? iris_metal_pixel_format(parsed.stencilFormat)
            : MTLPixelFormatInvalid;
        entry.depthStencil = iris_metal_depth_stencil_state(parsed);
        if (!entry.depthStencil) {
          [pipeline release];
          [runtimeVertex release];
          if (runtimeFragment) [runtimeFragment release];
          if (archiveDescriptor) [archiveDescriptor release];
          g_irisMetal4PipelineFailureCount.fetch_add(
              1, std::memory_order_relaxed);
          return kIrisMetal4PipelineFailed;
        }
        if (!g_irisMetal4RenderArchiveDescriptors) {
          g_irisMetal4RenderArchiveDescriptors =
              [[NSMutableArray alloc] init];
        }
        [g_irisMetal4RenderArchiveDescriptors addObject:archiveDescriptor];
        [archiveDescriptor release];
        g_irisMetal4Pipelines.emplace(key, entry);
        if (archiveHit) {
          g_irisMetal4PipelineCacheHitCount.fetch_add(
              1, std::memory_order_relaxed);
          return kIrisMetal4PipelineCacheHit;
        }
      }
      g_irisMetal4PipelineCompileCount.fetch_add(
          1, std::memory_order_relaxed);
      g_irisMetal4PipelineCacheDirty = true;
      return kIrisMetal4PipelineCompiled;
    }
    return kIrisMetal4PipelineUnsupported;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nFlushIrisMetal4PipelineCache(
    JNIEnv *, jclass) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_irisMetal4PipelineCacheMutex);
    return serialize_iris_metal4_pipeline_cache_after_translated_builds_locked()
        ? JNI_TRUE : JNI_FALSE;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMetal4PipelineAttemptCount(
    JNIEnv *, jclass) {
  return (jlong)g_irisMetal4PipelineAttemptCount.load(
      std::memory_order_acquire);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMetal4PipelineCompileCount(
    JNIEnv *, jclass) {
  return (jlong)g_irisMetal4PipelineCompileCount.load(
      std::memory_order_acquire);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMetal4PipelineCacheHitCount(
    JNIEnv *, jclass) {
  return (jlong)g_irisMetal4PipelineCacheHitCount.load(
      std::memory_order_acquire);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMetal4PipelineFailureCount(
    JNIEnv *, jclass) {
  return (jlong)g_irisMetal4PipelineFailureCount.load(
      std::memory_order_acquire);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMetal4PipelineStaleRecoveryCount(
    JNIEnv *, jclass) {
  return (jlong)g_irisMetal4PipelineStaleRecoveryCount.load(
      std::memory_order_acquire);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMetal4LivePipelineCount(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(g_irisMetal4PipelineCacheMutex);
  return (jlong)g_irisMetal4Pipelines.size();
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMetal4PipelineDrawAttemptCount(
    JNIEnv *, jclass) {
  return (jlong)g_irisMetal4PipelineDrawAttemptCount.load(
      std::memory_order_acquire);
}

static bool iris_shadow_exact_texture_format(const std::string &name,
                                             uint32_t bytesPerPixel,
                                             MTLPixelFormat &format) {
  // Three-component formats are routed through the type-aware repacker below.
  if (name == "rgb16-unorm" || name == "rgb16-snorm" ||
      name == "rgb16-float" || name == "rgb32-float" ||
      name == "rgb8-sint" || name == "rgb8-uint" ||
      name == "rgb16-sint" || name == "rgb16-uint" ||
      name == "rgb32-sint" || name == "rgb32-uint")
    return false;
  format = iris_metal_pixel_format(name);
  if (format == MTLPixelFormatInvalid)
    return false;
  uint32_t expected = 0;
  if (name == "r8-unorm" || name == "r8-snorm" || name == "r8-sint" ||
      name == "r8-uint" || name == "s8-uint")
    expected = 1;
  else if (name == "rg8-unorm" || name == "rg8-snorm" ||
           name == "r16-unorm" || name == "r16-snorm" ||
           name == "r16-float" || name == "r16-sint" ||
           name == "r16-uint" || name == "rg8-sint" ||
           name == "rg8-uint" || name == "d16-unorm")
    expected = 2;
  else if (name == "rgba8-unorm" || name == "rgba8-snorm" ||
           name == "rg16-unorm" || name == "rg16-snorm" ||
           name == "r32-float" || name == "r32-sint" ||
           name == "r32-uint" || name == "rg16-sint" ||
           name == "rg16-uint" || name == "rgba8-sint" ||
           name == "rgba8-uint" || name == "rgb10a2-unorm" ||
           name == "rgb10a2-uint" || name == "rg11b10-float" ||
           name == "rgb9e5-float" || name == "d32-float" ||
           name == "d24-unorm-s8-uint")
    expected = 4;
  else if (name == "rgba16-unorm" || name == "rgba16-snorm" ||
           name == "rgba16-float" || name == "rg32-float" ||
           name == "rgba16-sint" || name == "rgba16-uint" ||
           name == "rg32-sint" || name == "rg32-uint" ||
           name == "d32-float-s8-uint")
    expected = 8;
  else if (name == "rgba32-float" || name == "rgba32-sint" ||
           name == "rgba32-uint")
    expected = 16;
  return expected != 0 && expected == bytesPerPixel;
}

static bool iris_shadow_rgb_expansion(const std::string &name,
                                      uint32_t &componentBytes,
                                      uint32_t &alphaBits) {
  componentBytes = 0;
  alphaBits = 0;
  if (name == "rgb8-unorm") {
    componentBytes = 1;
    alphaBits = 0xffu;
  } else if (name == "rgb8-snorm") {
    componentBytes = 1;
    alphaBits = 0x7fu;
  } else if (name == "rgb8-sint" || name == "rgb8-uint") {
    componentBytes = 1;
    alphaBits = 1u;
  } else if (name == "rgb16-unorm") {
    componentBytes = 2;
    alphaBits = 0xffffu;
  } else if (name == "rgb16-snorm") {
    componentBytes = 2;
    alphaBits = 0x7fffu;
  } else if (name == "rgb16-float") {
    componentBytes = 2;
    alphaBits = 0x3c00u; // IEEE 754 binary16 1.0
  } else if (name == "rgb16-sint" || name == "rgb16-uint") {
    componentBytes = 2;
    alphaBits = 1u;
  } else if (name == "rgb32-float") {
    componentBytes = 4;
    alphaBits = 0x3f800000u; // IEEE 754 binary32 1.0
  } else if (name == "rgb32-sint" || name == "rgb32-uint") {
    componentBytes = 4;
    alphaBits = 1u;
  }
  return componentBytes != 0;
}

static bool iris_shadow_texture_upload(
    const IrisShadowTexture &captured, MTLPixelFormat &format,
    const uint8_t *&bytes, uint32_t &bytesPerPixel,
    std::vector<uint8_t> &expanded) {
  bytes = captured.bytes.data();
  bytesPerPixel = captured.bytesPerPixel;
  uint32_t componentBytes = 0;
  uint32_t alphaBits = 0;
  if (!iris_shadow_rgb_expansion(captured.format, componentBytes,
                                 alphaBits)) {
    return iris_shadow_exact_texture_format(captured.format,
        captured.bytesPerPixel, format);
  }
  if (captured.bytesPerPixel != componentBytes * 3u)
    return false;
  format = iris_metal_pixel_format(captured.format);
  if (format == MTLPixelFormatInvalid)
    return false;
  uint64_t pixels = (uint64_t)captured.width * captured.height;
  uint64_t inputStride = componentBytes * 3u;
  uint64_t outputStride = componentBytes * 4u;
  if (pixels == 0 ||
      pixels > kIrisShadowReplayMaximumTextureBytes / outputStride ||
      captured.bytes.size() != pixels * inputStride)
    return false;
  try {
    expanded.resize((size_t)(pixels * outputStride));
  } catch (...) {
    return false;
  }
  for (uint64_t pixel = 0; pixel < pixels; pixel++) {
    size_t source = (size_t)(pixel * inputStride);
    size_t destination = (size_t)(pixel * outputStride);
    std::memcpy(expanded.data() + destination,
                captured.bytes.data() + source, (size_t)inputStride);
    std::memcpy(expanded.data() + destination + inputStride,
                &alphaBits, componentBytes);
  }
  bytes = expanded.data();
  bytesPerPixel = (uint32_t)outputStride;
  return true;
}

static bool iris_shadow_color_bytes(MTLPixelFormat format,
                                    uint32_t &bytesPerPixel) {
  switch (format) {
  case MTLPixelFormatR8Unorm:
  case MTLPixelFormatR8Snorm:
  case MTLPixelFormatR8Uint:
  case MTLPixelFormatR8Sint:
    bytesPerPixel = 1; return true;
  case MTLPixelFormatRG8Unorm:
  case MTLPixelFormatRG8Snorm:
  case MTLPixelFormatRG8Uint:
  case MTLPixelFormatRG8Sint:
  case MTLPixelFormatR16Unorm:
  case MTLPixelFormatR16Snorm:
  case MTLPixelFormatR16Uint:
  case MTLPixelFormatR16Sint:
  case MTLPixelFormatR16Float:
    bytesPerPixel = 2; return true;
  case MTLPixelFormatRGBA8Unorm:
  case MTLPixelFormatRGBA8Snorm:
  case MTLPixelFormatRGBA8Uint:
  case MTLPixelFormatRGBA8Sint:
  case MTLPixelFormatRG16Unorm:
  case MTLPixelFormatRG16Snorm:
  case MTLPixelFormatRG16Uint:
  case MTLPixelFormatRG16Sint:
  case MTLPixelFormatRG16Float:
  case MTLPixelFormatR32Uint:
  case MTLPixelFormatR32Sint:
  case MTLPixelFormatR32Float:
  case MTLPixelFormatRGB10A2Unorm:
  case MTLPixelFormatRGB10A2Uint:
  case MTLPixelFormatRG11B10Float:
  case MTLPixelFormatRGB9E5Float:
    bytesPerPixel = 4; return true;
  case MTLPixelFormatRGBA16Unorm:
  case MTLPixelFormatRGBA16Snorm:
  case MTLPixelFormatRGBA16Uint:
  case MTLPixelFormatRGBA16Sint:
  case MTLPixelFormatRGBA16Float:
  case MTLPixelFormatRG32Uint:
  case MTLPixelFormatRG32Sint:
  case MTLPixelFormatRG32Float:
    bytesPerPixel = 8; return true;
  case MTLPixelFormatRGBA32Uint:
  case MTLPixelFormatRGBA32Sint:
  case MTLPixelFormatRGBA32Float:
    bytesPerPixel = 16; return true;
  default:
    return false;
  }
}

static bool iris_shadow_address_mode(uint32_t gl,
                                     MTLSamplerAddressMode &result) {
  switch (gl) {
  case 0x2901: result = MTLSamplerAddressModeRepeat; return true;
  case 0x812f: result = MTLSamplerAddressModeClampToEdge; return true;
  case 0x8370: result = MTLSamplerAddressModeMirrorRepeat; return true;
  case 0x812d: result = MTLSamplerAddressModeClampToBorderColor; return true;
  default: return false;
  }
}

using IrisMetal4SamplerKey = std::array<uint32_t, 18>;

struct IrisMetal4SamplerKeyHash {
  size_t operator()(const IrisMetal4SamplerKey &key) const {
    size_t hash = 1469598103934665603ULL;
    for (uint32_t value : key) {
      hash ^= value;
      hash *= 1099511628211ULL;
    }
    return hash;
  }
};

class IrisMetal4SamplerCache {
 public:
  id<MTLSamplerState> find(const IrisMetal4SamplerKey &key) {
    synchronizeGeneration();
    auto found = values_.find(key);
    return found == values_.end() || !found->second
        ? nil : [found->second retain];
  }

  void store(const IrisMetal4SamplerKey &key, id<MTLSamplerState> sampler) {
    if (!sampler)
      return;
    synchronizeGeneration();
    if (values_.size() >= kMaximumEntries ||
        values_.find(key) != values_.end())
      return;
    id<MTLSamplerState> retained = [sampler retain];
    try {
      values_.emplace(key, retained);
    } catch (...) {
      // The caller still owns and can use the uncached sampler.
      [retained release];
    }
  }

  ~IrisMetal4SamplerCache() { clear(); }

 private:
  static constexpr size_t kMaximumEntries = 256;

  void synchronizeGeneration() {
    uint64_t current = g_irisMetal4ArgumentEncoderGeneration.load(
        std::memory_order_acquire);
    if (generation_ == current)
      return;
    clear();
    generation_ = current;
  }

  void clear() {
    for (auto &entry : values_) {
      if (entry.second)
        [entry.second release];
    }
    values_.clear();
  }

  uint64_t generation_ = 0;
  std::unordered_map<IrisMetal4SamplerKey, id<MTLSamplerState>,
      IrisMetal4SamplerKeyHash> values_;
};

static thread_local IrisMetal4SamplerCache g_irisMetal4SamplerCache;

static IrisMetal4SamplerKey iris_shadow_sampler_key(
    const IrisShadowSampler &captured) {
  return {captured.minFilter, captured.magFilter, captured.wrapS,
      captured.wrapT, captured.wrapR, captured.compareMode,
      captured.compareFunc, captured.baseLevel, captured.maxLevel,
      captured.minLodBits, captured.maxLodBits, captured.lodBiasBits,
      captured.maxAnisotropyBits, captured.integerBorderColor ? 1u : 0u,
      captured.borderColor[0], captured.borderColor[1],
      captured.borderColor[2], captured.borderColor[3]};
}

static id<MTLSamplerState> iris_shadow_sampler(
    const IrisShadowSampler &captured, bool &supported) {
  supported = false;
  float minLod = 0.0f;
  float maxLod = 0.0f;
  float lodBias = 0.0f;
  float anisotropy = 0.0f;
  std::memcpy(&minLod, &captured.minLodBits, sizeof(float));
  std::memcpy(&maxLod, &captured.maxLodBits, sizeof(float));
  std::memcpy(&lodBias, &captured.lodBiasBits, sizeof(float));
  std::memcpy(&anisotropy, &captured.maxAnisotropyBits, sizeof(float));
  if (!std::isfinite(minLod) || !std::isfinite(maxLod) ||
      !std::isfinite(lodBias) || !std::isfinite(anisotropy) ||
      minLod > maxLod || captured.baseLevel != 0 ||
      captured.maxLevel < captured.baseLevel || lodBias != 0.0f ||
      anisotropy < 1.0f ||
      anisotropy > 16.0f || std::floor(anisotropy) != anisotropy)
    return nil;

  MTLSamplerMinMagFilter minFilter;
  MTLSamplerMipFilter mipFilter;
  switch (captured.minFilter) {
  case 0x2600:
    minFilter = MTLSamplerMinMagFilterNearest;
    mipFilter = MTLSamplerMipFilterNotMipmapped;
    break;
  case 0x2601:
    minFilter = MTLSamplerMinMagFilterLinear;
    mipFilter = MTLSamplerMipFilterNotMipmapped;
    break;
  case 0x2700:
    minFilter = MTLSamplerMinMagFilterNearest;
    mipFilter = MTLSamplerMipFilterNearest;
    break;
  case 0x2701:
    minFilter = MTLSamplerMinMagFilterLinear;
    mipFilter = MTLSamplerMipFilterNearest;
    break;
  case 0x2702:
    minFilter = MTLSamplerMinMagFilterNearest;
    mipFilter = MTLSamplerMipFilterLinear;
    break;
  case 0x2703:
    minFilter = MTLSamplerMinMagFilterLinear;
    mipFilter = MTLSamplerMipFilterLinear;
    break;
  default:
    return nil;
  }
  MTLSamplerMinMagFilter magFilter;
  if (captured.magFilter == 0x2600)
    magFilter = MTLSamplerMinMagFilterNearest;
  else if (captured.magFilter == 0x2601)
    magFilter = MTLSamplerMinMagFilterLinear;
  else
    return nil;
  MTLSamplerAddressMode s, t, r;
  if (!iris_shadow_address_mode(captured.wrapS, s) ||
      !iris_shadow_address_mode(captured.wrapT, t) ||
      !iris_shadow_address_mode(captured.wrapR, r))
    return nil;
  bool borderUsed = captured.wrapS == 0x812d || captured.wrapT == 0x812d ||
                    captured.wrapR == 0x812d;
  if (borderUsed && (captured.integerBorderColor ||
      captured.borderColor[0] != 0 || captured.borderColor[1] != 0 ||
      captured.borderColor[2] != 0 || captured.borderColor[3] != 0))
    return nil;
  MTLCompareFunction compare = MTLCompareFunctionNever;
  if (captured.compareMode == 0) {
    compare = MTLCompareFunctionNever;
  } else if (captured.compareMode == 0x884e &&
             captured.compareFunc >= 0x0200 &&
             captured.compareFunc <= 0x0207) {
    static const MTLCompareFunction values[] = {
        MTLCompareFunctionNever, MTLCompareFunctionLess,
        MTLCompareFunctionEqual, MTLCompareFunctionLessEqual,
        MTLCompareFunctionGreater, MTLCompareFunctionNotEqual,
        MTLCompareFunctionGreaterEqual, MTLCompareFunctionAlways};
    compare = values[captured.compareFunc - 0x0200];
  } else {
    return nil;
  }
  IrisMetal4SamplerKey samplerKey = iris_shadow_sampler_key(captured);
  id<MTLSamplerState> cached = g_irisMetal4SamplerCache.find(samplerKey);
  if (cached) {
    supported = true;
    return cached;
  }
  MTLSamplerDescriptor *descriptor = [[MTLSamplerDescriptor alloc] init];
  descriptor.minFilter = minFilter;
  descriptor.magFilter = magFilter;
  descriptor.mipFilter = mipFilter;
  descriptor.sAddressMode = s;
  descriptor.tAddressMode = t;
  descriptor.rAddressMode = r;
  descriptor.borderColor = MTLSamplerBorderColorTransparentBlack;
  descriptor.lodMinClamp = std::max(0.0f, minLod);
  descriptor.lodMaxClamp = std::max(0.0f,
      std::min(maxLod, (float)captured.maxLevel));
  descriptor.maxAnisotropy = (NSUInteger)anisotropy;
  descriptor.compareFunction = compare;
  descriptor.supportArgumentBuffers = YES;
  id<MTLSamplerState> sampler = [g_device newSamplerStateWithDescriptor:descriptor];
  [descriptor release];
  if (sampler)
    g_irisMetal4SamplerCache.store(samplerKey, sampler);
  supported = sampler != nil;
  return sampler;
}

static constexpr size_t kIrisMetal4TransientBufferPoolLimit = 8192;
static constexpr uint64_t kIrisMetal4TransientBufferPoolByteLimit =
    256ULL * 1024ULL * 1024ULL;

/**
 * Per-calling-thread pool for the shared buffers used by Iris argument
 * encoders and captured CPU buffer images.
 *
 * Metal 4's macOS 26 shared-buffer implementation internally suballocates
 * small MTLBuffers.  Releasing hundreds of them after one command buffer and
 * immediately allocating the same shapes on another Iris path exposed a
 * driver assertion in IOGPUMetalSuballocatorAllocate.  Retaining and reusing
 * a bounded set also removes that allocation churn from the eventual frame
 * path.  Buffers are returned only after synchronous command completion.
 */
struct IrisMetal4TransientBufferPool {
  std::unordered_map<NSUInteger, std::vector<id<MTLBuffer>>> available;
  size_t allocatedCount = 0;
  uint64_t allocatedBytes = 0;

  id<MTLBuffer> acquire(NSUInteger length) {
    if (!g_device || length == 0)
      return nil;
    auto found = available.find(length);
    if (found != available.end() && !found->second.empty()) {
      id<MTLBuffer> buffer = found->second.back();
      found->second.pop_back();
      return buffer;
    }
    if (allocatedCount >= kIrisMetal4TransientBufferPoolLimit ||
        (uint64_t)length > kIrisMetal4TransientBufferPoolByteLimit ||
        allocatedBytes >
            kIrisMetal4TransientBufferPoolByteLimit - (uint64_t)length) {
      return nil;
    }
    id<MTLBuffer> buffer = [g_device
        newBufferWithLength:length
                    options:MTLResourceStorageModeShared |
                            MTLResourceCPUCacheModeWriteCombined];
    if (!buffer)
      return nil;
    allocatedCount++;
    allocatedBytes += (uint64_t)length;
    return buffer;
  }

  void recycle(id<MTLBuffer> buffer) {
    if (!buffer)
      return;
    available[buffer.length].push_back(buffer);
  }

  ~IrisMetal4TransientBufferPool() {
    for (auto &bucket : available) {
      for (id<MTLBuffer> buffer : bucket.second)
        [buffer release];
    }
  }
};

static thread_local IrisMetal4TransientBufferPool
    g_irisMetal4TransientBufferPool;

struct IrisMetal4RetiredSubmission;

struct IrisShadowRuntimeResources {
  std::vector<id<MTLBuffer>> buffers;
  std::vector<uint8_t> pooledBufferOwnership;
  std::vector<id<MTLBuffer>> inlineBuffers;
  std::vector<id<MTLBuffer>> argumentBuffers;
  std::vector<id<MTLTexture>> sampledTextures;
  std::unordered_map<uint32_t, id<MTLTexture>> sampledByName;
  std::vector<id<MTLSamplerState>> samplers;
  std::vector<id<MTLTexture>> colorTargets;
  id<MTLTexture> depthTarget = nil;
  id<MTLTexture> stencilTarget = nil;
  id vertexTable = nil;
  id fragmentTable = nil;
  id computeTable = nil;

  void transferTo(IrisMetal4RetiredSubmission &submission);

  ~IrisShadowRuntimeResources() {
    if (vertexTable) [vertexTable release];
    if (fragmentTable) [fragmentTable release];
    if (computeTable) [computeTable release];
    for (id<MTLSamplerState> value : samplers) [value release];
    for (id<MTLBuffer> value : argumentBuffers)
      g_irisMetal4TransientBufferPool.recycle(value);
    for (id<MTLBuffer> value : inlineBuffers)
      g_irisMetal4TransientBufferPool.recycle(value);
    for (id<MTLTexture> value : sampledTextures) [value release];
    for (size_t index = 0; index < buffers.size(); index++) {
      if (index < pooledBufferOwnership.size() &&
          pooledBufferOwnership[index] != 0) {
        g_irisMetal4TransientBufferPool.recycle(buffers[index]);
      } else {
        [buffers[index] release];
      }
    }
    for (id<MTLTexture> value : colorTargets) {
      if (value) [value release];
    }
    if (depthTarget) [depthTarget release];
    if (stencilTarget && stencilTarget != depthTarget)
      [stencilTarget release];
  }
};

/**
 * Metal 4 command buffers don't retain the resources they reference.  A
 * submission whose shared-event completion has not been observed therefore
 * keeps its complete ownership graph in this bounded quarantine.  Successful
 * synchronous submissions are released immediately after the event boundary;
 * only genuinely unresolved GPU work consumes a slot.
 */
struct IrisMetal4RetiredSubmission {
  std::vector<id> submissionObjects;
  std::vector<id> resourceObjects;
  std::vector<id<MTLBuffer>> pooledBuffers;
  std::vector<IrisMetal4FrameArenaChunk> frameArenaChunks;
  // Store the retained Objective-C object without its macOS 15 protocol
  // spelling. Every assignment/use is guarded by the Metal 4 macOS 26 path,
  // while keeping the dylib's supported deployment target at macOS 14.
  id residency = nil;
  std::shared_ptr<Metal4ProbeState> feedbackState;

  ~IrisMetal4RetiredSubmission() {
    for (auto object = submissionObjects.rbegin();
         object != submissionObjects.rend(); ++object) {
      if (*object)
        [*object release];
    }
    if (residency) {
      [residency endResidency];
      [residency release];
    }
    for (auto object = resourceObjects.rbegin();
         object != resourceObjects.rend(); ++object) {
      if (*object)
        [*object release];
    }
    for (id<MTLBuffer> buffer : pooledBuffers)
      g_irisMetal4TransientBufferPool.recycle(buffer);
    for (IrisMetal4FrameArenaChunk &chunk : frameArenaChunks)
      g_irisMetal4FrameArenaBufferPool.recycle(chunk.buffer);
  }
};

static constexpr size_t kIrisMetal4RetiredSubmissionLimit = 16;
static thread_local std::vector<std::unique_ptr<IrisMetal4RetiredSubmission>>
    g_irisMetal4RetiredSubmissions;

static void iris_metal4_reap_retired_submissions()
    API_AVAILABLE(macos(26.0)) {
  auto current = g_irisMetal4RetiredSubmissions.begin();
  while (current != g_irisMetal4RetiredSubmissions.end()) {
    const IrisMetal4RetiredSubmission &submission = **current;
    if (iris_metal4_submission_completed(submission.feedbackState)) {
      current = g_irisMetal4RetiredSubmissions.erase(current);
    } else {
      ++current;
    }
  }
}

static bool iris_metal4_retired_submission_slot_available()
    API_AVAILABLE(macos(26.0)) {
  iris_metal4_reap_retired_submissions();
  return g_irisMetal4RetiredSubmissions.size() <
      kIrisMetal4RetiredSubmissionLimit;
}

void IrisShadowRuntimeResources::transferTo(
    IrisMetal4RetiredSubmission &submission) {
  if (vertexTable) {
    submission.resourceObjects.push_back(vertexTable);
    vertexTable = nil;
  }
  if (fragmentTable) {
    submission.resourceObjects.push_back(fragmentTable);
    fragmentTable = nil;
  }
  if (computeTable) {
    submission.resourceObjects.push_back(computeTable);
    computeTable = nil;
  }
  for (id<MTLSamplerState> value : samplers)
    submission.resourceObjects.push_back(value);
  samplers.clear();
  for (id<MTLBuffer> value : argumentBuffers)
    submission.pooledBuffers.push_back(value);
  argumentBuffers.clear();
  for (id<MTLBuffer> value : inlineBuffers)
    submission.pooledBuffers.push_back(value);
  inlineBuffers.clear();
  for (id<MTLTexture> value : sampledTextures)
    submission.resourceObjects.push_back(value);
  sampledTextures.clear();
  sampledByName.clear();
  for (size_t index = 0; index < buffers.size(); index++) {
    if (index < pooledBufferOwnership.size() &&
        pooledBufferOwnership[index] != 0) {
      submission.pooledBuffers.push_back(buffers[index]);
    } else {
      submission.resourceObjects.push_back(buffers[index]);
    }
  }
  buffers.clear();
  pooledBufferOwnership.clear();
  for (id<MTLTexture> value : colorTargets) {
    if (value)
      submission.resourceObjects.push_back(value);
  }
  colorTargets.clear();
  if (depthTarget)
    submission.resourceObjects.push_back(depthTarget);
  if (stencilTarget && stencilTarget != depthTarget)
    submission.resourceObjects.push_back(stencilTarget);
  depthTarget = nil;
  stencilTarget = nil;
}

static bool iris_metal4_retire_replay_submission(
    IrisShadowRuntimeResources &resources, IrisMetal4PipelineEntry &entry,
    id<MTLResidencySet> &residency,
    id<MTL4CommandAllocator> &allocator,
    id<MTL4CommandBuffer> &commandBuffer,
    MTL4RenderPassDescriptor *&pass, MTL4CommitOptions *&options,
    const std::shared_ptr<Metal4ProbeState> &feedbackState)
    API_AVAILABLE(macos(26.0)) {
  // The caller owns all resources and performs the normal destruction path
  // when this function returns false.  Once the shared event is complete that
  // path is both safe and preferable to consuming a quarantine slot.
  if (iris_metal4_submission_completed(feedbackState))
    return false;
  if (!iris_metal4_retired_submission_slot_available())
    return false;
  std::unique_ptr<IrisMetal4RetiredSubmission> submission(
      new (std::nothrow) IrisMetal4RetiredSubmission());
  if (!submission)
    return false;
  try {
    size_t resourceObjectCount = 2 + resources.samplers.size() +
        resources.sampledTextures.size() + resources.buffers.size() +
        resources.colorTargets.size() + 2;
    size_t pooledBufferCount = resources.argumentBuffers.size() +
        resources.inlineBuffers.size() + resources.buffers.size();
    submission->resourceObjects.reserve(resourceObjectCount);
    submission->pooledBuffers.reserve(pooledBufferCount);
    submission->submissionObjects.reserve(8);
    if (g_irisMetal4RetiredSubmissions.capacity() <
        kIrisMetal4RetiredSubmissionLimit) {
      g_irisMetal4RetiredSubmissions.reserve(
          kIrisMetal4RetiredSubmissionLimit);
    }
    resources.transferTo(*submission);
    if (entry.render)
      submission->submissionObjects.push_back(entry.render);
    if (entry.vertexFunction)
      submission->submissionObjects.push_back(entry.vertexFunction);
    if (entry.fragmentFunction)
      submission->submissionObjects.push_back(entry.fragmentFunction);
    if (entry.depthStencil)
      submission->submissionObjects.push_back(entry.depthStencil);
    if (pass)
      submission->submissionObjects.push_back(pass);
    if (options)
      submission->submissionObjects.push_back(options);
    if (allocator)
      submission->submissionObjects.push_back(allocator);
    if (commandBuffer)
      submission->submissionObjects.push_back(commandBuffer);
    submission->residency = residency;
    submission->feedbackState = feedbackState;
    g_irisMetal4RetiredSubmissions.push_back(std::move(submission));
  } catch (...) {
    return false;
  }
  entry.render = nil;
  entry.vertexFunction = nil;
  entry.fragmentFunction = nil;
  entry.depthStencil = nil;
  residency = nil;
  allocator = nil;
  commandBuffer = nil;
  pass = nil;
  options = nil;
  return true;
}

struct IrisTextureBufferFormat {
  MTLPixelFormat pixelFormat = MTLPixelFormatInvalid;
  NSUInteger bytesPerTexel = 0;
};

static bool iris_texture_buffer_format(uint32_t glInternalFormat,
                                       IrisTextureBufferFormat &result) {
  switch (glInternalFormat) {
  case 0x8229: result = {MTLPixelFormatR8Unorm, 1}; break;       // GL_R8
  case 0x822a: result = {MTLPixelFormatR16Unorm, 2}; break;     // GL_R16
  case 0x822b: result = {MTLPixelFormatRG8Unorm, 2}; break;     // GL_RG8
  case 0x822c: result = {MTLPixelFormatRG16Unorm, 4}; break;    // GL_RG16
  case 0x822d: result = {MTLPixelFormatR16Float, 2}; break;     // GL_R16F
  case 0x822e: result = {MTLPixelFormatR32Float, 4}; break;     // GL_R32F
  case 0x822f: result = {MTLPixelFormatRG16Float, 4}; break;    // GL_RG16F
  case 0x8230: result = {MTLPixelFormatRG32Float, 8}; break;    // GL_RG32F
  case 0x8231: result = {MTLPixelFormatR8Sint, 1}; break;       // GL_R8I
  case 0x8232: result = {MTLPixelFormatR8Uint, 1}; break;       // GL_R8UI
  case 0x8233: result = {MTLPixelFormatR16Sint, 2}; break;      // GL_R16I
  case 0x8234: result = {MTLPixelFormatR16Uint, 2}; break;      // GL_R16UI
  case 0x8235: result = {MTLPixelFormatR32Sint, 4}; break;      // GL_R32I
  case 0x8236: result = {MTLPixelFormatR32Uint, 4}; break;      // GL_R32UI
  case 0x8237: result = {MTLPixelFormatRG8Sint, 2}; break;      // GL_RG8I
  case 0x8238: result = {MTLPixelFormatRG8Uint, 2}; break;      // GL_RG8UI
  case 0x8239: result = {MTLPixelFormatRG16Sint, 4}; break;     // GL_RG16I
  case 0x823a: result = {MTLPixelFormatRG16Uint, 4}; break;     // GL_RG16UI
  case 0x823b: result = {MTLPixelFormatRG32Sint, 8}; break;     // GL_RG32I
  case 0x823c: result = {MTLPixelFormatRG32Uint, 8}; break;     // GL_RG32UI
  case 0x8058: result = {MTLPixelFormatRGBA8Unorm, 4}; break;   // GL_RGBA8
  case 0x805b: result = {MTLPixelFormatRGBA16Unorm, 8}; break;  // GL_RGBA16
  case 0x8814: result = {MTLPixelFormatRGBA32Float, 16}; break; // GL_RGBA32F
  case 0x881a: result = {MTLPixelFormatRGBA16Float, 8}; break;  // GL_RGBA16F
  case 0x8d70: result = {MTLPixelFormatRGBA32Uint, 16}; break;  // GL_RGBA32UI
  case 0x8d76: result = {MTLPixelFormatRGBA16Uint, 8}; break;   // GL_RGBA16UI
  case 0x8d7c: result = {MTLPixelFormatRGBA8Uint, 4}; break;    // GL_RGBA8UI
  case 0x8d82: result = {MTLPixelFormatRGBA32Sint, 16}; break;  // GL_RGBA32I
  case 0x8d88: result = {MTLPixelFormatRGBA16Sint, 8}; break;   // GL_RGBA16I
  case 0x8d8e: result = {MTLPixelFormatRGBA8Sint, 4}; break;    // GL_RGBA8I
  case 0x8f94: result = {MTLPixelFormatR8Snorm, 1}; break;      // GL_R8_SNORM
  case 0x8f95: result = {MTLPixelFormatRG8Snorm, 2}; break;     // GL_RG8_SNORM
  case 0x8f97: result = {MTLPixelFormatRGBA8Snorm, 4}; break;   // GL_RGBA8_SNORM
  case 0x8f98: result = {MTLPixelFormatR16Snorm, 2}; break;     // GL_R16_SNORM
  case 0x8f99: result = {MTLPixelFormatRG16Snorm, 4}; break;    // GL_RG16_SNORM
  case 0x8f9b: result = {MTLPixelFormatRGBA16Snorm, 8}; break;  // GL_RGBA16_SNORM
  default: return false;
  }
  return true;
}

struct IrisMetal4ArgumentEncoderKey {
  const void *function = nullptr;
  uint32_t bufferIndex = 0;

  bool operator==(const IrisMetal4ArgumentEncoderKey &other) const {
    return function == other.function && bufferIndex == other.bufferIndex;
  }
};

struct IrisMetal4ArgumentEncoderKeyHash {
  size_t operator()(const IrisMetal4ArgumentEncoderKey &key) const {
    size_t pointerHash = std::hash<const void *>{}(key.function);
    return pointerHash ^ ((size_t)key.bufferIndex +
        0x9e3779b97f4a7c15ULL + (pointerHash << 6) +
        (pointerHash >> 2));
  }
};

class IrisMetal4ArgumentEncoderCache {
 public:
  id<MTLArgumentEncoder> acquire(id<MTLFunction> function,
                                 uint32_t bufferIndex) {
    if (!function)
      return nil;
    synchronizeGeneration();
    IrisMetal4ArgumentEncoderKey key{(const void *)function, bufferIndex};
    auto found = encoders_.find(key);
    if (found != encoders_.end() && found->second)
      return [found->second retain];
    id<MTLArgumentEncoder> encoder =
        [function newArgumentEncoderWithBufferIndex:bufferIndex];
    if (!encoder)
      return nil;
    if (encoders_.size() >= kMaximumEntries)
      return encoder;
    try {
      encoders_.emplace(key, encoder);
      return [encoder retain];
    } catch (...) {
      [encoder release];
      return nil;
    }
  }

  ~IrisMetal4ArgumentEncoderCache() { clear(); }

 private:
  static constexpr size_t kMaximumEntries = 1024;

  void synchronizeGeneration() {
    uint64_t current = g_irisMetal4ArgumentEncoderGeneration.load(
        std::memory_order_acquire);
    if (generation_ == current)
      return;
    clear();
    generation_ = current;
  }

  void clear() {
    for (auto &entry : encoders_) {
      if (entry.second)
        [entry.second release];
    }
    encoders_.clear();
  }

  uint64_t generation_ = 0;
  std::unordered_map<IrisMetal4ArgumentEncoderKey,
      id<MTLArgumentEncoder>, IrisMetal4ArgumentEncoderKeyHash> encoders_;
};

static thread_local IrisMetal4ArgumentEncoderCache
    g_irisMetal4ArgumentEncoderCache;

class IrisMetal4ArgumentTableFactory {
 public:
  id make() API_AVAILABLE(macos(26.0)) {
    if (!descriptor_) {
      MTL4ArgumentTableDescriptor *descriptor =
          [[MTL4ArgumentTableDescriptor alloc] init];
      descriptor.maxBufferBindCount = 31;
      descriptor.maxTextureBindCount = 0;
      descriptor.maxSamplerStateBindCount = 0;
      descriptor.initializeBindings = YES;
      descriptor_ = descriptor;
    }
    NSError *error = nil;
    return descriptor_
        ? [g_device newArgumentTableWithDescriptor:
              (MTL4ArgumentTableDescriptor *)descriptor_ error:&error]
        : nil;
  }

  ~IrisMetal4ArgumentTableFactory() {
    if (descriptor_)
      [descriptor_ release];
  }

 private:
  // Keep the long-lived reference dynamically typed so loading the dylib on
  // macOS 14/15 does not require a Metal 4 Objective-C class symbol.
  id descriptor_ = nil;
};

static thread_local IrisMetal4ArgumentTableFactory
    g_irisMetal4ArgumentTableFactory;

static int iris_shadow_prepare_arguments(
    const IrisShadowStageArguments &stage,
    id<MTLFunction> function, IrisShadowRuntimeResources &resources,
    IrisMetal4FrameBufferArena *frameArena = nullptr)
    API_AVAILABLE(macos(26.0)) {
  if (!function)
    return stage.arguments.empty() ? 1 : 0;
  id *tableSlot = nullptr;
  if (stage.stage == 0) {
    tableSlot = &resources.vertexTable;
  } else if (stage.stage == 4) {
    tableSlot = &resources.fragmentTable;
  } else if (stage.stage == 5) {
    tableSlot = &resources.computeTable;
  } else {
    return 0;
  }
  *tableSlot = g_irisMetal4ArgumentTableFactory.make();
  if (!*tableSlot)
    return -1;

  size_t begin = 0;
  while (begin < stage.arguments.size()) {
    uint32_t outer = stage.arguments[begin].argumentBufferIndex;
    size_t end = begin + 1;
    while (end < stage.arguments.size() &&
           stage.arguments[end].argumentBufferIndex == outer)
      end++;
    id<MTLArgumentEncoder> argumentEncoder =
        g_irisMetal4ArgumentEncoderCache.acquire(function, outer);
    if (!argumentEncoder || argumentEncoder.encodedLength == 0) {
      if (argumentEncoder) [argumentEncoder release];
      return 0;
    }
    id<MTLBuffer> argumentBuffer = nil;
    NSUInteger argumentBufferOffset = 0;
    IrisMetal4FrameBufferArena::Slice argumentSlice;
    if (frameArena) {
      if (frameArena->allocate(argumentEncoder.encodedLength,
              std::max((NSUInteger)256, argumentEncoder.alignment),
              argumentSlice)) {
        argumentBuffer = argumentSlice.buffer;
        argumentBufferOffset = argumentSlice.offset;
      }
    } else {
      argumentBuffer = g_irisMetal4TransientBufferPool.acquire(
          argumentEncoder.encodedLength);
    }
    if (!argumentBuffer) {
      [argumentEncoder release];
      return -1;
    }
    std::memset((uint8_t *)argumentBuffer.contents + argumentBufferOffset,
                0, argumentEncoder.encodedLength);
    [argumentEncoder setArgumentBuffer:argumentBuffer
                                offset:argumentBufferOffset];
    if (!frameArena)
      resources.argumentBuffers.push_back(argumentBuffer);
    [(id<MTL4ArgumentTable>)*tableSlot
        setAddress:argumentBuffer.gpuAddress + argumentBufferOffset
           atIndex:outer];

    for (size_t index = begin; index < end; index++) {
      const IrisShadowArgument &argument = stage.arguments[index];
      if (argument.kind == 1) {
        id<MTLBuffer> inlineBuffer = nil;
        NSUInteger inlineBufferOffset = 0;
        IrisMetal4FrameBufferArena::Slice inlineSlice;
        if (frameArena) {
          if (frameArena->allocate(argument.inlineBytes.size(), 256,
                                   inlineSlice)) {
            inlineBuffer = inlineSlice.buffer;
            inlineBufferOffset = inlineSlice.offset;
          }
        } else {
          inlineBuffer = g_irisMetal4TransientBufferPool.acquire(
              argument.inlineBytes.size());
        }
        if (!inlineBuffer) {
          [argumentEncoder release];
          return -1;
        }
        std::memcpy((uint8_t *)inlineBuffer.contents + inlineBufferOffset,
                    argument.inlineBytes.data(),
                    argument.inlineBytes.size());
        if (!frameArena)
          resources.inlineBuffers.push_back(inlineBuffer);
        [argumentEncoder setBuffer:inlineBuffer offset:inlineBufferOffset
                            atIndex:argument.id];
      } else if (argument.kind == 2) {
        [argumentEncoder setBuffer:resources.buffers[argument.reference]
                            offset:0 atIndex:argument.id];
      } else if (argument.kind == 3) {
        auto texture = resources.sampledByName.find(argument.reference);
        if (texture == resources.sampledByName.end()) {
          [argumentEncoder release];
          return -1;
        }
        [argumentEncoder setTexture:texture->second atIndex:argument.id];
      } else if (argument.kind == 7) {
        IrisTextureBufferFormat format;
        id<MTLBuffer> storage = resources.buffers[argument.reference];
        if (!storage ||
            !iris_texture_buffer_format(argument.auxiliary, format) ||
            format.bytesPerTexel == 0 || storage.length == 0 ||
            storage.length % format.bytesPerTexel != 0) {
          [argumentEncoder release];
          return 0;
        }
        NSUInteger texels = storage.length / format.bytesPerTexel;
        if (texels == 0 ||
            texels > std::numeric_limits<NSUInteger>::max() /
                         format.bytesPerTexel) {
          [argumentEncoder release];
          return 0;
        }
        // A texture view backed by an MTLBuffer is a linear texture. Metal
        // validates bytesPerRow even for MTLTextureTypeTextureBuffer, so zero
        // is not a sentinel here: it must cover the complete texel row.
        NSUInteger bytesPerRow = texels * format.bytesPerTexel;
        MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
            textureBufferDescriptorWithPixelFormat:format.pixelFormat
                                             width:texels
                                   resourceOptions:MTLResourceStorageModeShared
                                             usage:MTLTextureUsageShaderRead];
        id<MTLTexture> texture = [storage
            newTextureWithDescriptor:descriptor
                                offset:0
                           bytesPerRow:bytesPerRow];
        if (!texture) {
          [argumentEncoder release];
          return 0;
        }
        resources.sampledTextures.push_back(texture);
        [argumentEncoder setTexture:texture atIndex:argument.id];
      } else if (argument.kind == 4 || argument.kind == 6) {
        // The packet does not yet carry the SPIR-V sampled type needed to
        // create a type-correct zero texture/default sampler pair.
        [argumentEncoder release];
        return 0;
      } else if (argument.kind == 5) {
        bool samplerSupported = false;
        id<MTLSamplerState> sampler = iris_shadow_sampler(argument.sampler,
                                                          samplerSupported);
        if (!samplerSupported || !sampler) {
          if (sampler) [sampler release];
          [argumentEncoder release];
          return 0;
        }
        resources.samplers.push_back(sampler);
        [argumentEncoder setSamplerState:sampler atIndex:argument.id];
      }
    }
    [argumentEncoder release];
    begin = end;
  }
  return 1;
}

namespace {

struct IrisMetal4GraphFeedbackCopy {
  id<MTLTexture> source = nil;
  id<MTLTexture> snapshot = nil;
};

struct IrisMetal4GraphPreparedDraw {
  IrisShadowReplayPacket packet;
  IrisMetal4PipelineEntry pipeline;
  IrisShadowRuntimeResources resources;
  std::vector<IrisMetal4GraphFeedbackCopy> feedbackCopies;
  std::vector<uint32_t> colorTargetMips;
  uint32_t depthTargetMip = 0;
  uint32_t stencilTargetMip = 0;
  MTLPrimitiveType primitiveType = MTLPrimitiveTypeTriangle;
  std::vector<id> additionalAllocations;
  std::vector<id> encodedObjects;
  bool pipelineRetained = false;

  ~IrisMetal4GraphPreparedDraw() {
    for (id object : encodedObjects) {
      if (object)
        [object release];
    }
    if (!pipelineRetained)
      return;
    if (pipeline.render) [pipeline.render release];
    if (pipeline.compute) [pipeline.compute release];
    if (pipeline.vertexFunction) [pipeline.vertexFunction release];
    if (pipeline.fragmentFunction) [pipeline.fragmentFunction release];
    if (pipeline.computeFunction) [pipeline.computeFunction release];
    if (pipeline.depthStencil) [pipeline.depthStencil release];
  }
};

static bool iris_graph_texture_override_format_compatible(
    MTLPixelFormat graphFormat, MTLPixelFormat capturedFormat) {
  // CGL cannot expose a packed RG11B10F attachment as an IOSurface, so the GL
  // validation bridge expands it to RGBA16F.  A Metal-owned graph samples the
  // original packed texture directly; both are floating-point shader inputs.
  return graphFormat == capturedFormat ||
      (graphFormat == MTLPixelFormatRG11B10Float &&
       capturedFormat == MTLPixelFormatRGBA16Float);
}

static bool iris_input_handoff_format_compatible(
    const IrisMetal4InputHandoff &handoff,
    MTLPixelFormat logicalFormat) {
  if (!handoff.metalTexture)
    return false;
  MTLPixelFormat physicalFormat = handoff.metalTexture.pixelFormat;
  if (physicalFormat == logicalFormat)
    return true;
  // CGL exposes the 8-bit IOSurface as BGRA. Metal must use the matching
  // physical pixel format so texture sampling returns logical RGBA channels.
  return handoff.kind == 1 && logicalFormat == MTLPixelFormatRGBA8Unorm &&
      physicalFormat == MTLPixelFormatBGRA8Unorm;
}

static bool iris_graph_prepare_input_textures(
    const std::vector<IrisShadowTexture> &inputs,
    std::vector<IrisMetal4GraphFramePreparedInputTexture> &textures,
    jlong &reason) API_AVAILABLE(macos(26.0)) {
  reason = 0;
  try {
    textures.reserve(inputs.size());
  } catch (...) {
    reason = 5;
    return false;
  }
  uint64_t uploadedBytes = 0;
  for (const auto &captured : inputs) {
    if (captured.storageKind != 1 || captured.layer != 0 ||
        captured.mipLevel != 0) {
      reason = kIrisGraphReasonDrawTextureSubresourceUnsupported;
      return false;
    }
    MTLPixelFormat format = MTLPixelFormatInvalid;
    const uint8_t *uploadBytes = nullptr;
    uint32_t uploadBytesPerPixel = 0;
    std::vector<uint8_t> expanded;
    if (!iris_shadow_texture_upload(captured, format, uploadBytes,
                                    uploadBytesPerPixel, expanded)) {
      reason = kIrisGraphReasonDrawTextureFormatUnsupported;
      return false;
    }
    uint64_t byteLength = (uint64_t)captured.width * captured.height;
    if (byteLength > UINT64_MAX / uploadBytesPerPixel ||
        !iris_shadow_add_bounded(uploadedBytes,
            byteLength * uploadBytesPerPixel,
            kIrisShadowReplayMaximumTextureBytes)) {
      reason = kIrisGraphReasonDrawTextureByteBudgetExceeded;
      return false;
    }
    id<MTLTexture> texture = nil;
    @try {
      MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
          texture2DDescriptorWithPixelFormat:format width:captured.width
                                        height:captured.height mipmapped:NO];
      descriptor.storageMode = MTLStorageModeShared;
      descriptor.usage = MTLTextureUsageShaderRead;
      texture = [g_device newTextureWithDescriptor:descriptor];
      if (texture) {
        [texture replaceRegion:MTLRegionMake2D(0, 0, captured.width,
                                               captured.height)
                  mipmapLevel:0 withBytes:uploadBytes
                  bytesPerRow:(NSUInteger)captured.width *
                              uploadBytesPerPixel];
      }
    } @catch (NSException *) {
      if (texture)
        [texture release];
      reason = 5;
      return false;
    }
    if (!texture) {
      reason = 5;
      return false;
    }
    try {
      IrisMetal4GraphFramePreparedInputTexture prepared;
      prepared.glName = captured.glName;
      prepared.format = captured.format;
      prepared.width = captured.width;
      prepared.height = captured.height;
      prepared.layer = captured.layer;
      prepared.mipLevel = captured.mipLevel;
      prepared.bytesPerPixel = captured.bytesPerPixel;
      prepared.texture = texture;
      textures.push_back(std::move(prepared));
    } catch (...) {
      [texture release];
      reason = 5;
      return false;
    }
  }
  return true;
}

static IrisMetal4GraphPreparedDraw *iris_graph_prepare_draw(
    const IrisMetal4GraphFrameOperation &operation,
    const std::unordered_map<uint32_t, id<MTLTexture>> &graphTextures,
    const std::vector<id<MTLBuffer>> &graphInputBuffers,
    const std::vector<IrisMetal4GraphFramePreparedInputTexture>
        &graphInputTextures,
    IrisMetal4FrameBufferArena *frameArena,
    std::vector<uint64_t> &inputSurfaceLeases,
    int &outcome, jlong &reason) API_AVAILABLE(macos(26.0)) {
  outcome = -1;
  reason = 5;
  std::unique_ptr<IrisMetal4GraphPreparedDraw> prepared(
      new (std::nothrow) IrisMetal4GraphPreparedDraw());
  if (!prepared)
    return nullptr;
  try {
    bool parsedReplay = operation.borrowedReplayPacket
        ? parse_iris_shadow_graph_replay_packet(
            operation.borrowedReplayPacket, operation.replayPacketLength,
            prepared->packet)
        : parse_iris_shadow_graph_replay_packet(operation.replayPacket,
            prepared->packet);
    if (!parsedReplay) {
      reason = 5;
      return nullptr;
    }

    IrisShadowReplayPacket &packet = prepared->packet;
    bool computePacket = operation.kind == 6;
    if (computePacket != (packet.draw.kind == 4)) {
      outcome = 0;
      reason = kIrisGraphReasonDrawPipelineStateMismatch;
      return nullptr;
    }
    {
      std::lock_guard<std::mutex> lock(g_irisMetal4PipelineCacheMutex);
      auto found = g_irisMetal4Pipelines.find(operation.pipelineKey);
      if (found == g_irisMetal4Pipelines.end() ||
          (computePacket ? !found->second.compute : !found->second.render)) {
        outcome = 0;
        reason = kIrisGraphReasonDrawPipelineUnavailable;
        return nullptr;
      }
      prepared->pipeline = found->second;
      if (computePacket) {
        [prepared->pipeline.compute retain];
        if (prepared->pipeline.computeFunction)
          [prepared->pipeline.computeFunction retain];
      } else {
        [prepared->pipeline.render retain];
        if (prepared->pipeline.vertexFunction)
          [prepared->pipeline.vertexFunction retain];
        if (prepared->pipeline.fragmentFunction)
          [prepared->pipeline.fragmentFunction retain];
        if (prepared->pipeline.depthStencil)
          [prepared->pipeline.depthStencil retain];
      }
      prepared->pipelineRetained = true;
    }

    IrisMetal4PipelineEntry &entry = prepared->pipeline;
    prepared->encodedObjects.reserve(1);
    if (!computePacket) {
      uint32_t packetTopology = packet.draw.primitiveMode <= 5
          ? packet.draw.primitiveMode : UINT32_MAX;
      if (!iris_metal_primitive_type(entry.topology,
                                     prepared->primitiveType) ||
          packetTopology != iris_metal_effective_packet_topology(
              entry.topology) || entry.rasterSampleCount == 0 ||
          (entry.colorFormats.empty() &&
           entry.depthFormat == MTLPixelFormatInvalid &&
           entry.stencilFormat == MTLPixelFormatInvalid)) {
        outcome = 0;
        reason = kIrisGraphReasonDrawPipelineStateMismatch;
        return nullptr;
      }
    } else if (packet.draw.groupsX == 0 || packet.draw.groupsY == 0 ||
               packet.draw.groupsZ == 0 ||
               packet.draw.localSizeX == 0 ||
               packet.draw.localSizeY == 0 ||
               packet.draw.localSizeZ == 0 ||
               (uint64_t)packet.draw.localSizeX *
                   packet.draw.localSizeY * packet.draw.localSizeZ > 1024ULL) {
      outcome = 0;
      reason = kIrisGraphReasonDrawPipelineStateMismatch;
      return nullptr;
    }

    auto graphTexture = [&](uint32_t resourceId) -> id<MTLTexture> {
      auto found = graphTextures.find(resourceId);
      return found == graphTextures.end() ? nil : found->second;
    };
    auto validTarget = [&](id<MTLTexture> texture,
                           MTLPixelFormat format,
                           uint32_t mipLevel) -> bool {
      if (!texture || texture.pixelFormat != format ||
          texture.sampleCount != entry.rasterSampleCount ||
          (texture.usage & MTLTextureUsageRenderTarget) == 0 ||
          mipLevel >= texture.mipmapLevelCount) {
        return false;
      }
      NSUInteger mipWidth = std::max((NSUInteger)1,
          texture.width >> mipLevel);
      NSUInteger mipHeight = std::max((NSUInteger)1,
          texture.height >> mipLevel);
      return mipWidth == packet.width && mipHeight == packet.height;
    };

    prepared->resources.colorTargets.resize(entry.colorFormats.size(), nil);
    prepared->colorTargetMips.assign(operation.colorTargets.size(), 0);
    for (size_t targetIndex = 0; targetIndex < operation.colorTargets.size();
         targetIndex++) {
      const auto &target = operation.colorTargets[targetIndex];
      uint32_t mipLevel = targetIndex < operation.colorTargetMips.size()
          ? operation.colorTargetMips[targetIndex] : 0;
      if (target.first >= entry.colorFormats.size() ||
          entry.colorFormats[target.first] == MTLPixelFormatInvalid) {
        outcome = 0;
        reason = kIrisGraphReasonDrawColorSlotMismatch;
        return nullptr;
      }
      id<MTLTexture> texture = graphTexture(target.second);
      if (!validTarget(texture, entry.colorFormats[target.first], mipLevel)) {
        outcome = 0;
        reason = kIrisGraphReasonDrawColorTargetMismatch;
        return nullptr;
      }
      prepared->resources.colorTargets[target.first] = [texture retain];
      prepared->colorTargetMips[targetIndex] = mipLevel;
    }
    for (size_t slot = 0; slot < entry.colorFormats.size(); slot++) {
      if ((entry.colorFormats[slot] != MTLPixelFormatInvalid) !=
          (prepared->resources.colorTargets[slot] != nil)) {
        outcome = 0;
        reason = kIrisGraphReasonDrawColorCoverageMismatch;
        return nullptr;
      }
    }

    if ((entry.depthFormat != MTLPixelFormatInvalid) !=
        (operation.depthResource >= 0) ||
        (entry.stencilFormat != MTLPixelFormatInvalid) !=
        (operation.stencilResource >= 0)) {
      outcome = 0;
      reason = kIrisGraphReasonDrawDepthStencilPresenceMismatch;
      return nullptr;
    }
    if (operation.depthResource >= 0) {
      id<MTLTexture> texture = graphTexture(
          (uint32_t)operation.depthResource);
      if (!validTarget(texture, entry.depthFormat, operation.depthMipLevel)) {
        outcome = 0;
        reason = kIrisGraphReasonDrawDepthTargetMismatch;
        return nullptr;
      }
      prepared->resources.depthTarget = [texture retain];
      prepared->depthTargetMip = operation.depthMipLevel;
    }
    if (operation.stencilResource >= 0) {
      id<MTLTexture> texture = graphTexture(
          (uint32_t)operation.stencilResource);
      if (!validTarget(texture, entry.stencilFormat, operation.stencilMipLevel)) {
        outcome = 0;
        reason = kIrisGraphReasonDrawStencilTargetMismatch;
        return nullptr;
      }
      if (operation.stencilResource == operation.depthResource) {
        if (entry.stencilFormat != entry.depthFormat ||
            operation.stencilMipLevel != operation.depthMipLevel) {
          outcome = 0;
          reason = kIrisGraphReasonDrawDepthStencilAliasMismatch;
          return nullptr;
        }
        prepared->resources.stencilTarget =
            prepared->resources.depthTarget;
        prepared->stencilTargetMip = prepared->depthTargetMip;
      } else {
        if (entry.stencilFormat == entry.depthFormat &&
            entry.depthFormat != MTLPixelFormatInvalid) {
          outcome = 0;
          reason = kIrisGraphReasonDrawDepthStencilAliasMismatch;
          return nullptr;
        }
        prepared->resources.stencilTarget = [texture retain];
        prepared->stencilTargetMip = operation.stencilMipLevel;
      }
    }

    for (const auto &image : packet.buffers) {
      id<MTLBuffer> buffer = nil;
      if (image.storageKind == 3) {
        uint64_t externalIndex = image.sharedHandle - 1;
        if (externalIndex >= graphInputBuffers.size()) {
          outcome = 0;
          reason = kIrisGraphReasonDrawExternalBufferIndexMismatch;
          return nullptr;
        }
        if (!graphInputBuffers[(size_t)externalIndex]) {
          outcome = 0;
          reason = kIrisGraphReasonDrawExternalBufferMissing;
          return nullptr;
        }
        if (graphInputBuffers[(size_t)externalIndex].length !=
            image.byteLength) {
          dbg("WARN: Iris graph external buffer mismatch index=%llu "
              "count=%zu expected=%u actual=%llu\n",
              (unsigned long long)externalIndex,
              graphInputBuffers.size(), image.byteLength,
              (unsigned long long)(externalIndex < graphInputBuffers.size() &&
                      graphInputBuffers[(size_t)externalIndex]
                  ? graphInputBuffers[(size_t)externalIndex].length : 0));
          outcome = 0;
          reason = kIrisGraphReasonDrawExternalBufferLengthMismatch;
          return nullptr;
        }
        buffer = [graphInputBuffers[(size_t)externalIndex] retain];
      } else if (image.storageKind == 2) {
        id<MTLBuffer> resident = iris_metal4_resident_buffer(
            image.sharedHandle, image.byteLength);
        if (resident)
          buffer = [resident retain];
        if (!buffer) {
          outcome = 0;
          reason = kIrisGraphReasonDrawBufferResidencyMismatch;
          return nullptr;
        }
      } else {
        buffer = g_irisMetal4TransientBufferPool.acquire(
            (NSUInteger)image.byteLength);
        if (buffer)
          std::memcpy(buffer.contents, image.bytes.data(), image.byteLength);
      }
      if (!buffer)
        return nullptr;
      prepared->resources.buffers.push_back(buffer);
      prepared->resources.pooledBufferOwnership.push_back(
          image.storageKind == 1 ? 1 : 0);
      prepared->additionalAllocations.push_back(
          (id<MTLAllocation>)buffer);
    }

    uint64_t sampledTextureBytes = 0;
    size_t overrideUses = 0;
    for (const auto &captured : packet.textures) {
      auto override = operation.textureOverrides.find(captured.glName);
      if (override != operation.textureOverrides.end()) {
        id<MTLTexture> texture = graphTexture(override->second);
        MTLPixelFormat expectedFormat =
            iris_metal_pixel_format(captured.format);
        NSUInteger expectedWidth = texture &&
            captured.mipLevel < texture.mipmapLevelCount
                ? std::max((NSUInteger)1,
                    texture.width >> captured.mipLevel) : 0;
        NSUInteger expectedHeight = texture &&
            captured.mipLevel < texture.mipmapLevelCount
                ? std::max((NSUInteger)1,
                    texture.height >> captured.mipLevel) : 0;
        uint64_t logicalBytes = (uint64_t)captured.width *
            captured.height * captured.bytesPerPixel;
        if (!texture || expectedFormat == MTLPixelFormatInvalid ||
            !iris_graph_texture_override_format_compatible(
                texture.pixelFormat, expectedFormat) ||
            captured.layer != 0 ||
            captured.mipLevel >= texture.mipmapLevelCount ||
            expectedWidth != captured.width ||
            expectedHeight != captured.height ||
            (texture.usage & (MTLTextureUsageShaderRead |
                MTLTextureUsageShaderWrite)) == 0 ||
            !iris_shadow_add_bounded(sampledTextureBytes, logicalBytes,
                kIrisShadowReplayMaximumTextureBytes)) {
          outcome = 0;
          reason = kIrisGraphReasonDrawTextureOverrideMismatch;
          return nullptr;
        }
        bool feedbackTarget = false;
        if (operation.colorTargets.size() > 0) {
          feedbackTarget = std::any_of(operation.colorTargets.begin(),
              operation.colorTargets.end(), [&](const auto &target) {
                return target.second == override->second;
              });
        }
        feedbackTarget = feedbackTarget
            || (operation.depthResource >= 0 &&
                (uint32_t)operation.depthResource == override->second)
            || (operation.stencilResource >= 0 &&
                (uint32_t)operation.stencilResource == override->second);
        if (feedbackTarget) {
          if (texture.sampleCount != 1 ||
              texture.textureType != MTLTextureType2D ||
              texture.mipmapLevelCount == 0 ||
              texture.width == 0 || texture.height == 0) {
            outcome = 0;
            reason = kIrisGraphReasonDrawFeedbackSnapshotUnsupported;
            return nullptr;
          }
          MTLTextureDescriptor *feedbackDescriptor =
              [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:
                  texture.pixelFormat width:texture.width height:texture.height
                  mipmapped:texture.mipmapLevelCount > 1];
          feedbackDescriptor.storageMode = MTLStorageModePrivate;
          feedbackDescriptor.hazardTrackingMode = MTLHazardTrackingModeTracked;
          feedbackDescriptor.sampleCount = 1;
          feedbackDescriptor.mipmapLevelCount = texture.mipmapLevelCount;
          feedbackDescriptor.usage = MTLTextureUsageShaderRead;
          MTLSizeAndAlign feedbackSize =
              [g_device heapTextureSizeAndAlignWithDescriptor:
                  feedbackDescriptor];
          if (feedbackSize.size == 0 ||
              feedbackSize.size > kIrisShadowReplayMaximumTextureBytes) {
            outcome = 0;
            reason = kIrisGraphReasonDrawFeedbackSnapshotUnsupported;
            return nullptr;
          }
          id<MTLTexture> snapshot =
              [g_device newTextureWithDescriptor:feedbackDescriptor];
          if (!snapshot) {
            outcome = 0;
            reason = kIrisGraphReasonDrawFeedbackSnapshotUnsupported;
            return nullptr;
          }
          prepared->feedbackCopies.push_back({texture, snapshot});
          prepared->resources.sampledTextures.push_back(snapshot);
          prepared->resources.sampledByName.emplace(captured.glName, snapshot);
          prepared->additionalAllocations.push_back(
              (id<MTLAllocation>)snapshot);
          overrideUses++;
          continue;
        }
        id<MTLTexture> retainedTexture = [texture retain];
        prepared->resources.sampledTextures.push_back(retainedTexture);
        prepared->resources.sampledByName.emplace(captured.glName,
                                                   retainedTexture);
        overrideUses++;
        continue;
      }

      if (captured.storageKind == 4) {
        uint64_t externalIndex = captured.sharedHandle - 1;
        if (externalIndex >= graphInputTextures.size()) {
          outcome = 0;
          reason = kIrisGraphReasonDrawExternalTextureIndexMismatch;
          return nullptr;
        }
        const auto &input = graphInputTextures[(size_t)externalIndex];
        if (!input.texture) {
          outcome = 0;
          reason = kIrisGraphReasonDrawExternalTextureMissing;
          return nullptr;
        }
        uint64_t logicalBytes = (uint64_t)captured.width *
            captured.height * captured.bytesPerPixel;
        if (input.glName != captured.glName ||
            input.format != captured.format ||
            input.width != captured.width ||
            input.height != captured.height ||
            input.layer != captured.layer ||
            input.mipLevel != captured.mipLevel ||
            input.bytesPerPixel != captured.bytesPerPixel ||
            (input.texture.usage & MTLTextureUsageShaderRead) == 0 ||
            !iris_shadow_add_bounded(sampledTextureBytes, logicalBytes,
                kIrisShadowReplayMaximumTextureBytes)) {
          outcome = 0;
          reason = kIrisGraphReasonDrawExternalTextureMetadataMismatch;
          return nullptr;
        }
        id<MTLTexture> texture = [input.texture retain];
        prepared->resources.sampledTextures.push_back(texture);
        prepared->resources.sampledByName.emplace(captured.glName, texture);
        prepared->additionalAllocations.push_back(
            (id<MTLAllocation>)texture);
        continue;
      }

      if (captured.storageKind == 2) {
        IrisMetal4InputHandoff *shared = iris_metal4_find_input_handoff(
            captured.glName, captured.sharedHandle);
        MTLPixelFormat expectedFormat =
            iris_metal_pixel_format(captured.format);
        if (!shared || shared->width != captured.width ||
            shared->height != captured.height ||
            !iris_input_handoff_format_compatible(
                *shared, expectedFormat) ||
            !iris_metal4_track_input_surface_lease(
                *shared, inputSurfaceLeases) ||
            !iris_shadow_add_bounded(sampledTextureBytes,
                (uint64_t)captured.width * captured.height *
                    captured.bytesPerPixel,
                kIrisShadowReplayMaximumTextureBytes)) {
          outcome = 0;
          reason = kIrisGraphReasonDrawSharedTextureMismatch;
          return nullptr;
        }
        IrisMetal4InputHandoffReadiness readiness =
            iris_metal4_ensure_input_handoff_ready(*shared);
        if (readiness != IrisMetal4InputHandoffReadiness::READY) {
          outcome = 0;
          reason = readiness ==
                  IrisMetal4InputHandoffReadiness::FENCE_TIMEOUT
              ? kIrisGraphReasonDrawSharedTextureFenceTimeout
              : kIrisGraphReasonDrawSharedTextureMismatch;
          return nullptr;
        }
        id<MTLTexture> texture = [shared->metalTexture retain];
        prepared->resources.sampledTextures.push_back(texture);
        prepared->resources.sampledByName.emplace(captured.glName, texture);
        prepared->additionalAllocations.push_back(
            (id<MTLAllocation>)texture);
        continue;
      }

      if (captured.storageKind == 3) {
        outcome = 0;
        reason = kIrisGraphReasonDrawTextureOverrideMismatch;
        return nullptr;
      }

      MTLPixelFormat format = MTLPixelFormatInvalid;
      const uint8_t *uploadBytes = nullptr;
      uint32_t uploadBytesPerPixel = 0;
      std::vector<uint8_t> expanded;
      if (captured.layer != 0 || captured.mipLevel != 0) {
        outcome = 0;
        reason = kIrisGraphReasonDrawTextureSubresourceUnsupported;
        return nullptr;
      }
      if (!iris_shadow_texture_upload(captured, format, uploadBytes,
                                      uploadBytesPerPixel, expanded)) {
        outcome = 0;
        reason = kIrisGraphReasonDrawTextureFormatUnsupported;
        return nullptr;
      }
      if (!iris_shadow_add_bounded(sampledTextureBytes,
              (uint64_t)captured.width * captured.height *
                  uploadBytesPerPixel,
              kIrisShadowReplayMaximumTextureBytes)) {
        outcome = 0;
        reason = kIrisGraphReasonDrawTextureByteBudgetExceeded;
        return nullptr;
      }
      MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
          texture2DDescriptorWithPixelFormat:format width:captured.width
                                        height:captured.height mipmapped:NO];
      descriptor.storageMode = MTLStorageModeShared;
      descriptor.usage = MTLTextureUsageShaderRead;
      id<MTLTexture> texture = [g_device newTextureWithDescriptor:descriptor];
      if (!texture)
        return nullptr;
      [texture replaceRegion:MTLRegionMake2D(0, 0, captured.width,
                                             captured.height)
                mipmapLevel:0 withBytes:uploadBytes
                bytesPerRow:(NSUInteger)captured.width *
                            uploadBytesPerPixel];
      prepared->resources.sampledTextures.push_back(texture);
      prepared->resources.sampledByName.emplace(captured.glName, texture);
      prepared->additionalAllocations.push_back(
          (id<MTLAllocation>)texture);
    }
    if (overrideUses != operation.textureOverrides.size()) {
      outcome = 0;
      reason = kIrisGraphReasonDrawUnusedTextureOverride;
      return nullptr;
    }

    for (const auto &stage : packet.stages) {
      id<MTLFunction> argumentFunction = nil;
      if (stage.stage == 0) {
        argumentFunction = entry.vertexFunction;
      } else if (stage.stage == 4) {
        argumentFunction = entry.fragmentFunction;
      } else if (stage.stage == 5) {
        argumentFunction = entry.computeFunction;
      }
      if (!argumentFunction) {
        outcome = 0;
        reason = kIrisGraphReasonDrawArgumentBindingUnsupported;
        return nullptr;
      }
      int result = iris_shadow_prepare_arguments(stage, argumentFunction,
          prepared->resources, frameArena);
      if (result <= 0) {
        outcome = result;
        reason = result == 0
            ? kIrisGraphReasonDrawArgumentBindingUnsupported : 5;
        return nullptr;
      }
    }
    for (id<MTLBuffer> buffer : prepared->resources.inlineBuffers) {
      prepared->additionalAllocations.push_back(
          (id<MTLAllocation>)buffer);
    }
    for (id<MTLBuffer> buffer : prepared->resources.argumentBuffers) {
      prepared->additionalAllocations.push_back(
          (id<MTLAllocation>)buffer);
    }

    if (!computePacket && !packet.vertexBuffers.empty() &&
        !prepared->resources.vertexTable) {
      prepared->resources.vertexTable =
          g_irisMetal4ArgumentTableFactory.make();
      if (!prepared->resources.vertexTable)
        return nullptr;
    }
    if (!computePacket) {
      for (const auto &binding : packet.vertexBuffers) {
        [(id<MTL4ArgumentTable>)prepared->resources.vertexTable
            setAddress:prepared->resources.buffers[binding.second].gpuAddress
               atIndex:binding.first];
      }
    }

    outcome = 1;
    reason = 0;
    return prepared.release();
  } catch (...) {
    outcome = -1;
    reason = 5;
    return nullptr;
  }
}

static NSUInteger iris_graph_prepared_draw_allocation_count(
    const IrisMetal4GraphPreparedDraw *draw) API_AVAILABLE(macos(26.0)) {
  return draw ? draw->additionalAllocations.size() : 0;
}

static void iris_graph_prepared_draw_append_residency_allocations(
    const IrisMetal4GraphPreparedDraw *draw,
    std::vector<id<MTLAllocation>> &allocations)
    API_AVAILABLE(macos(26.0)) {
  if (!draw)
    return;
  for (id allocation : draw->additionalAllocations) {
    if (allocation)
      allocations.push_back((id<MTLAllocation>)allocation);
  }
}

static bool iris_graph_prepared_draw_samples_pass_target(
    const IrisMetal4GraphPreparedDraw *draw) API_AVAILABLE(macos(26.0)) {
  if (!draw)
    return true;
  const IrisShadowRuntimeResources &resources = draw->resources;
  for (id<MTLTexture> sampled : resources.sampledTextures) {
    if (!sampled)
      continue;
    if (sampled == resources.depthTarget ||
        sampled == resources.stencilTarget) {
      return true;
    }
    for (id<MTLTexture> target : resources.colorTargets) {
      if (sampled == target)
        return true;
    }
  }
  return false;
}

static bool iris_graph_prepared_draw_can_share_pass(
    const IrisMetal4GraphPreparedDraw *first,
    const IrisMetal4GraphPreparedDraw *next) API_AVAILABLE(macos(26.0)) {
  if (!first || !next ||
      iris_graph_prepared_draw_samples_pass_target(first) ||
      iris_graph_prepared_draw_samples_pass_target(next) ||
      first->packet.width != next->packet.width ||
      first->packet.height != next->packet.height ||
      first->pipeline.rasterSampleCount !=
          next->pipeline.rasterSampleCount ||
      first->resources.colorTargets.size() !=
          next->resources.colorTargets.size() ||
      first->resources.depthTarget != next->resources.depthTarget ||
      first->resources.stencilTarget != next->resources.stencilTarget) {
    return false;
  }
  if (first->colorTargetMips != next->colorTargetMips ||
      first->depthTargetMip != next->depthTargetMip ||
      first->stencilTargetMip != next->stencilTargetMip) {
    return false;
  }
  for (size_t slot = 0; slot < first->resources.colorTargets.size();
       slot++) {
    if (first->resources.colorTargets[slot] !=
        next->resources.colorTargets[slot]) {
      return false;
    }
  }
  return true;
}

static int iris_graph_encode_feedback_copies(
    IrisMetal4GraphPreparedDraw *draw,
    id<MTL4CommandBuffer> commandBuffer, bool applyBarrier,
    MTLStages graphStages, jlong &reason) API_AVAILABLE(macos(26.0)) {
  if (!draw || !commandBuffer) {
    reason = 5;
    return -1;
  }
  if (draw->feedbackCopies.empty()) {
    return 1;
  }
  id<MTL4ComputeCommandEncoder> encoder =
      [commandBuffer computeCommandEncoder];
  if (!encoder) {
    reason = kIrisGraphReasonDrawFeedbackSnapshotUnsupported;
    return -1;
  }
  if (applyBarrier) {
    [encoder barrierAfterQueueStages:graphStages
                        beforeStages:graphStages
                   visibilityOptions:MTL4VisibilityOptionDevice];
  }
  for (const auto &copy : draw->feedbackCopies) {
    if (!copy.source || !copy.snapshot ||
        copy.source.sampleCount != 1 ||
        copy.snapshot.mipmapLevelCount != copy.source.mipmapLevelCount) {
      [encoder endEncoding];
      reason = kIrisGraphReasonDrawFeedbackSnapshotUnsupported;
      return 0;
    }
    for (NSUInteger level = 0;
         level < copy.source.mipmapLevelCount; level++) {
      NSUInteger width = std::max((NSUInteger)1, copy.source.width >> level);
      NSUInteger height = std::max((NSUInteger)1, copy.source.height >> level);
      [encoder copyFromTexture:copy.source
                   sourceSlice:0
                   sourceLevel:level
                  sourceOrigin:MTLOriginMake(0, 0, 0)
                    sourceSize:MTLSizeMake(width, height, 1)
                      toTexture:copy.snapshot
               destinationSlice:0
               destinationLevel:level
              destinationOrigin:MTLOriginMake(0, 0, 0)];
    }
  }
  [encoder endEncoding];
  reason = 0;
  return 1;
}

static int iris_graph_encode_prepared_draw_commands(
    IrisMetal4GraphPreparedDraw *draw,
    id<MTL4RenderCommandEncoder> encoder,
    jlong &reason) API_AVAILABLE(macos(26.0)) {
  if (!draw || !encoder) {
    reason = 5;
    return -1;
  }
  IrisShadowReplayPacket &packet = draw->packet;
  IrisMetal4PipelineEntry &entry = draw->pipeline;
  IrisShadowRuntimeResources &resources = draw->resources;
  [encoder setRenderPipelineState:entry.render];
  [encoder setDepthStencilState:entry.depthStencil];
  [encoder setCullMode:entry.cullMode];
  [encoder setFrontFacingWinding:entry.frontFacingWinding];
  [encoder setTriangleFillMode:entry.triangleFillMode];
  [encoder setDepthClipMode:entry.depthClipMode];
  uint32_t offsetBit = entry.topology == 0 ? 1u
      : (entry.topology >= 1 && entry.topology <= 3 ? 2u : 4u);
  if ((entry.polygonOffsetMask & offsetBit) != 0) {
    [encoder setDepthBias:entry.depthBias slopeScale:entry.slopeScale
                    clamp:entry.depthBiasClamp];
  } else {
    // A fused encoder retains dynamic raster state between draws.
    [encoder setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
  }
  [encoder setStencilFrontReferenceValue:entry.frontStencilReference
                      backReferenceValue:entry.backStencilReference];
  MTLViewport viewport = {(double)packet.viewport.x,
      (double)((int64_t)packet.height - packet.viewport.y -
               packet.viewport.height),
      (double)packet.viewport.width, (double)packet.viewport.height,
      0.0, 1.0};
  [encoder setViewport:viewport];
  int64_t sx0 = packet.scissorEnabled
      ? std::max<int64_t>(0, packet.scissor.x) : 0;
  int64_t sy0 = packet.scissorEnabled
      ? std::max<int64_t>(0, packet.scissor.y) : 0;
  int64_t sx1 = packet.scissorEnabled
      ? std::min<int64_t>(packet.width,
          (int64_t)packet.scissor.x + packet.scissor.width)
      : packet.width;
  int64_t sy1 = packet.scissorEnabled
      ? std::min<int64_t>(packet.height,
          (int64_t)packet.scissor.y + packet.scissor.height)
      : packet.height;
  if (sx1 <= sx0 || sy1 <= sy0) {
    reason = 0;
    return 1;
  }
  MTLScissorRect scissor = {(NSUInteger)sx0,
      (NSUInteger)((int64_t)packet.height - sy1),
      (NSUInteger)(sx1 - sx0), (NSUInteger)(sy1 - sy0)};
  [encoder setScissorRect:scissor];
  if (resources.vertexTable) {
    [encoder setArgumentTable:resources.vertexTable
                      atStages:MTLRenderStageVertex];
  }
  if (resources.fragmentTable) {
    [encoder setArgumentTable:resources.fragmentTable
                      atStages:MTLRenderStageFragment];
  }
  if (packet.draw.kind == 1) {
    [encoder drawPrimitives:draw->primitiveType
                 vertexStart:(NSUInteger)packet.draw.firstVertex
                 vertexCount:packet.draw.vertexCount
               instanceCount:packet.draw.instanceCount
                baseInstance:packet.draw.baseInstance];
  } else {
    if (packet.indexBufferImage < 0 ||
        (size_t)packet.indexBufferImage >= resources.buffers.size()) {
      reason = kIrisGraphReasonDrawIndexBufferMissing;
      return 0;
    }
    id<MTLBuffer> indices =
        resources.buffers[(size_t)packet.indexBufferImage];
    MTLIndexType indexType = packet.draw.indexElementBytes == 2
        ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32;
    for (const auto &indexed : packet.draw.indexed) {
      uint64_t required = (uint64_t)indexed.count *
                          packet.draw.indexElementBytes;
      if (indexed.offset % packet.draw.indexElementBytes != 0 ||
          indexed.offset > indices.length ||
          required > indices.length - indexed.offset) {
        reason = kIrisGraphReasonDrawIndexRangeInvalid;
        return 0;
      }
      [encoder drawIndexedPrimitives:draw->primitiveType
                          indexCount:indexed.count
                           indexType:indexType
                         indexBuffer:indices.gpuAddress + indexed.offset
                   indexBufferLength:indices.length - indexed.offset
                       instanceCount:packet.draw.instanceCount
                          baseVertex:indexed.baseVertex
                        baseInstance:packet.draw.baseInstance];
    }
  }
  reason = 0;
  return 1;
}

static int iris_graph_encode_prepared_draw_run(
    std::vector<IrisMetal4GraphPreparedDraw *> &draws,
    size_t begin, size_t end,
    id<MTL4CommandBuffer> commandBuffer, bool applyBarrier,
    MTLStages graphStages,
    const IrisMetal4GraphFrameOperation &drawOperation,
    const std::vector<const IrisMetal4GraphFrameOperation *> &loadClears,
    jlong &reason) API_AVAILABLE(macos(26.0)) {
  if (!commandBuffer || begin >= end || end > draws.size() ||
      !draws[begin]) {
    reason = 5;
    return -1;
  }
  IrisMetal4GraphPreparedDraw *first = draws[begin];
  IrisShadowReplayPacket &packet = first->packet;
  IrisMetal4PipelineEntry &entry = first->pipeline;
  IrisShadowRuntimeResources &resources = first->resources;
  if (!draws[begin]->feedbackCopies.empty()) {
    int feedbackOutcome = iris_graph_encode_feedback_copies(
        draws[begin], commandBuffer, applyBarrier, graphStages, reason);
    if (feedbackOutcome <= 0) {
      return feedbackOutcome;
    }
  }
  MTL4RenderPassDescriptor *pass =
      [[MTL4RenderPassDescriptor alloc] init];
  if (!pass) {
    reason = 5;
    return -1;
  }
  pass.defaultRasterSampleCount = entry.rasterSampleCount;
  pass.renderTargetWidth = packet.width;
  pass.renderTargetHeight = packet.height;
  for (size_t slot = 0; slot < resources.colorTargets.size(); slot++) {
    if (!resources.colorTargets[slot])
      continue;
    MTLRenderPassColorAttachmentDescriptor *attachment =
        pass.colorAttachments[slot];
    attachment.texture = resources.colorTargets[slot];
    attachment.level = iris_graph_draw_color_target_mip(
        drawOperation, (uint32_t)slot);
    attachment.loadAction = MTLLoadActionLoad;
    attachment.storeAction = MTLStoreActionStore;
  }
  if (resources.depthTarget) {
    pass.depthAttachment.texture = resources.depthTarget;
    pass.depthAttachment.level = drawOperation.depthMipLevel;
    pass.depthAttachment.loadAction = MTLLoadActionLoad;
    pass.depthAttachment.storeAction = MTLStoreActionStore;
  }
  if (resources.stencilTarget) {
    pass.stencilAttachment.texture = resources.stencilTarget;
    pass.stencilAttachment.level = drawOperation.stencilMipLevel;
    pass.stencilAttachment.loadAction = MTLLoadActionLoad;
    pass.stencilAttachment.storeAction = MTLStoreActionStore;
  }
  for (const IrisMetal4GraphFrameOperation *clear : loadClears) {
    if (!clear || clear->kind != 1) {
      [pass release];
      reason = 5;
      return -1;
    }
    bool matched = false;
    if (clear->aspect == 0) {
      for (const auto &target : drawOperation.colorTargets) {
        if (target.second != clear->firstResource ||
            target.first >= resources.colorTargets.size() ||
            !resources.colorTargets[target.first]) {
          continue;
        }
        MTLRenderPassColorAttachmentDescriptor *attachment =
            pass.colorAttachments[target.first];
        attachment.loadAction = MTLLoadActionClear;
        attachment.clearColor = MTLClearColorMake(
            iris_graph_frame_clear_component(clear->valueKind,
                clear->rawValues[0]),
            iris_graph_frame_clear_component(clear->valueKind,
                clear->rawValues[1]),
            iris_graph_frame_clear_component(clear->valueKind,
                clear->rawValues[2]),
            iris_graph_frame_clear_component(clear->valueKind,
                clear->rawValues[3]));
        matched = true;
        break;
      }
    }
    if (clear->aspect == 1 || clear->aspect == 3) {
      if (drawOperation.depthResource >= 0 &&
          (uint32_t)drawOperation.depthResource == clear->firstResource &&
          resources.depthTarget) {
        pass.depthAttachment.loadAction = MTLLoadActionClear;
        pass.depthAttachment.clearDepth =
            iris_graph_frame_clear_component(clear->valueKind,
                clear->rawValues[0]);
        matched = true;
      }
    }
    if (clear->aspect == 2 || clear->aspect == 3) {
      if (drawOperation.stencilResource >= 0 &&
          (uint32_t)drawOperation.stencilResource == clear->firstResource &&
          resources.stencilTarget) {
        size_t valueIndex = clear->aspect == 3 ? 1 : 0;
        pass.stencilAttachment.loadAction = MTLLoadActionClear;
        pass.stencilAttachment.clearStencil =
            (uint32_t)clear->rawValues[valueIndex];
        matched = true;
      }
    }
    if (!matched) {
      [pass release];
      reason = 5;
      return -1;
    }
  }
  id<MTL4RenderCommandEncoder> encoder =
      [commandBuffer renderCommandEncoderWithDescriptor:pass];
  first->encodedObjects.push_back(pass);
  if (!encoder) {
    reason = 5;
    return -1;
  }
  if (applyBarrier || !draws[begin]->feedbackCopies.empty()) {
    [encoder barrierAfterQueueStages:graphStages
                        beforeStages:graphStages
                   visibilityOptions:MTL4VisibilityOptionDevice];
  }
  for (size_t index = begin; index < end; index++) {
    int outcome = iris_graph_encode_prepared_draw_commands(
        draws[index], encoder, reason);
    if (outcome <= 0) {
      [encoder endEncoding];
      return outcome;
    }
  }
  [encoder endEncoding];
  reason = 0;
  return 1;
}

static void iris_graph_destroy_prepared_draw(
    IrisMetal4GraphPreparedDraw *draw) API_AVAILABLE(macos(26.0)) {
  delete draw;
}

static bool iris_graph_retire_submission(
    std::vector<IrisMetal4GraphPreparedDraw *> &preparedDraws,
    std::vector<IrisMetal4GraphFrameRetainedResource> &retained,
    IrisMetal4FrameBufferArena &frameArena,
    std::vector<id> &encodedObjects, id<MTLBuffer> &readback,
    id<MTLResidencySet> &residency,
    id<MTL4CommandAllocator> &allocator,
    id<MTL4CommandBuffer> &commandBuffer, MTL4CommitOptions *&options,
    const std::shared_ptr<Metal4ProbeState> &feedbackState)
    API_AVAILABLE(macos(26.0)) {
  if (iris_metal4_submission_completed(feedbackState))
    return false;
  if (!iris_metal4_retired_submission_slot_available())
    return false;
  std::unique_ptr<IrisMetal4RetiredSubmission> submission(
      new (std::nothrow) IrisMetal4RetiredSubmission());
  if (!submission)
    return false;
  try {
    size_t resourceObjectCount = retained.size() + (readback ? 1 : 0);
    size_t pooledBufferCount = 0;
    size_t submissionObjectCount = encodedObjects.size() + 3;
    for (IrisMetal4GraphPreparedDraw *draw : preparedDraws) {
      if (!draw)
        continue;
      IrisShadowRuntimeResources &resources = draw->resources;
      resourceObjectCount += 4 + resources.samplers.size() +
          resources.sampledTextures.size() + resources.buffers.size() +
          resources.colorTargets.size();
      pooledBufferCount += resources.argumentBuffers.size() +
          resources.inlineBuffers.size() + resources.buffers.size();
      submissionObjectCount += 4 + draw->encodedObjects.size();
    }
    submission->resourceObjects.reserve(resourceObjectCount);
    submission->pooledBuffers.reserve(pooledBufferCount);
    submission->submissionObjects.reserve(submissionObjectCount);
    if (g_irisMetal4RetiredSubmissions.capacity() <
        kIrisMetal4RetiredSubmissionLimit) {
      g_irisMetal4RetiredSubmissions.reserve(
          kIrisMetal4RetiredSubmissionLimit);
    }

    for (IrisMetal4GraphFrameRetainedResource &resource : retained) {
      if (resource.texture) {
        submission->resourceObjects.push_back(resource.texture);
        resource.texture = nil;
      }
    }
    for (IrisMetal4GraphPreparedDraw *&draw : preparedDraws) {
      if (!draw)
        continue;
      draw->resources.transferTo(*submission);
      IrisMetal4PipelineEntry &entry = draw->pipeline;
      if (entry.render) {
        submission->submissionObjects.push_back(entry.render);
        entry.render = nil;
      }
      if (entry.vertexFunction) {
        submission->submissionObjects.push_back(entry.vertexFunction);
        entry.vertexFunction = nil;
      }
      if (entry.fragmentFunction) {
        submission->submissionObjects.push_back(entry.fragmentFunction);
        entry.fragmentFunction = nil;
      }
      if (entry.compute) {
        submission->submissionObjects.push_back(entry.compute);
        entry.compute = nil;
      }
      if (entry.computeFunction) {
        submission->submissionObjects.push_back(entry.computeFunction);
        entry.computeFunction = nil;
      }
      if (entry.depthStencil) {
        submission->submissionObjects.push_back(entry.depthStencil);
        entry.depthStencil = nil;
      }
      for (id object : draw->encodedObjects)
        submission->submissionObjects.push_back(object);
      draw->encodedObjects.clear();
      draw->additionalAllocations.clear();
      draw->pipelineRetained = false;
      delete draw;
      draw = nullptr;
    }
    for (id object : encodedObjects)
      submission->submissionObjects.push_back(object);
    encodedObjects.clear();
    if (readback) {
      submission->resourceObjects.push_back(readback);
      readback = nil;
    }
    if (options) {
      submission->submissionObjects.push_back(options);
      options = nil;
    }
    if (allocator) {
      submission->submissionObjects.push_back(allocator);
      allocator = nil;
    }
    if (commandBuffer) {
      submission->submissionObjects.push_back(commandBuffer);
      commandBuffer = nil;
    }
    submission->residency = residency;
    residency = nil;
    submission->feedbackState = feedbackState;
    submission->frameArenaChunks = frameArena.takeChunks();
    g_irisMetal4RetiredSubmissions.push_back(std::move(submission));
    return true;
  } catch (...) {
    return false;
  }
}

} // namespace

static uint64_t iris_shadow_fnv1a64(const uint8_t *bytes, size_t length) {
  uint64_t hash = 1469598103934665603ULL;
  for (size_t index = 0; index < length; index++) {
    hash ^= bytes[index];
    hash *= 1099511628211ULL;
  }
  return hash;
}

static jlongArray iris_shadow_result(JNIEnv *env, jlong status, jlong hash,
                                     jlong width, jlong height,
                                     jlong reason = 0) {
  jlong values[] = {status, hash, width, height, reason};
  jlongArray result = env->NewLongArray(5);
  if (!result)
    return nullptr;
  env->SetLongArrayRegion(result, 0, 5, values);
  return env->ExceptionCheck() ? nullptr : result;
}

static jlongArray run_iris_metal4_replay(
    JNIEnv *env, jstring pipelineKeyValue, jbyteArray packetValue,
    bool directPresentation) {
  std::lock_guard<std::mutex> executionLock(g_irisMetal4ExecutionMutex);
  @autoreleasepool {
    g_irisMetal4LastReplayRgba8.clear();
    if (directPresentation && g_irisMetal4PendingCutoverSurface) {
      CFRelease(g_irisMetal4PendingCutoverSurface);
      g_irisMetal4PendingCutoverSurface = nullptr;
      g_irisMetal4PendingCutoverWidth = 0;
      g_irisMetal4PendingCutoverHeight = 0;
    }
    if (!g_device || !g_metal4CommandQueue ||
        !g_metal4RuntimeVerified.load(std::memory_order_acquire))
      return iris_shadow_result(env, 0, 0, 0, 0, 1);
    if (!pipelineKeyValue || !packetValue)
      return iris_shadow_result(env, -1, 0, 0, 0);
    if (@available(macOS 26.0, *)) {
      const char *pipelineKey =
          env->GetStringUTFChars(pipelineKeyValue, nullptr);
      if (!pipelineKey)
        return nullptr;
      std::string key(pipelineKey);
      bool validKey = valid_iris_pipeline_key(pipelineKey);
      env->ReleaseStringUTFChars(pipelineKeyValue, pipelineKey);
      if (!validKey)
        return iris_shadow_result(env, -1, 0, 0, 0);
      jsize packetLength = env->GetArrayLength(packetValue);
      if (packetLength <= 0 ||
          packetLength > kIrisShadowReplayMaximumPacketBytes)
        return iris_shadow_result(env, -1, 0, 0, 0);
      std::vector<jbyte> packetBytes;
      try {
        packetBytes.resize((size_t)packetLength);
      } catch (...) {
        return iris_shadow_result(env, -1, 0, 0, 0);
      }
      env->GetByteArrayRegion(packetValue, 0, packetLength,
                              packetBytes.data());
      if (env->ExceptionCheck())
        return nullptr;
      IrisShadowReplayPacket packet;
      if (!parse_iris_shadow_replay_packet(packetBytes, packet))
        return iris_shadow_result(env, -1, 0, 0, 0);

      IrisMetal4PipelineEntry entry;
      {
        std::lock_guard<std::mutex> lock(g_irisMetal4PipelineCacheMutex);
        auto found = g_irisMetal4Pipelines.find(key);
        if (found == g_irisMetal4Pipelines.end() || !found->second.render)
          return iris_shadow_result(env, -1, 0, 0, 0);
        entry = found->second;
        [entry.render retain];
        if (entry.vertexFunction) [entry.vertexFunction retain];
        if (entry.fragmentFunction) [entry.fragmentFunction retain];
        if (entry.depthStencil) [entry.depthStencil retain];
      }
      auto releaseEntry = [&]() {
        [entry.render release];
        if (entry.vertexFunction) [entry.vertexFunction release];
        if (entry.fragmentFunction) [entry.fragmentFunction release];
        if (entry.depthStencil) [entry.depthStencil release];
      };
      MTLPrimitiveType primitiveType = MTLPrimitiveTypeTriangle;
      uint32_t packetTopology = packet.draw.primitiveMode <= 5
          ? packet.draw.primitiveMode : UINT32_MAX;
      if (!iris_metal_primitive_type(entry.topology, primitiveType) ||
          packetTopology != iris_metal_effective_packet_topology(
              entry.topology) || entry.rasterSampleCount != 1 ||
          entry.colorFormats.empty()) {
        releaseEntry();
        return iris_shadow_result(env, 0, 0, packet.width, packet.height, 2);
      }
      if (directPresentation &&
          (entry.colorFormats.size() != 1 ||
           entry.colorFormats[0] != MTLPixelFormatRGBA8Unorm ||
           entry.depthFormat != MTLPixelFormatInvalid ||
           entry.stencilFormat != MTLPixelFormatInvalid)) {
        releaseEntry();
        return iris_shadow_result(env, 0, 0, packet.width, packet.height, 8);
      }
      if (!iris_metal4_retired_submission_slot_available()) {
        releaseEntry();
        return iris_shadow_result(env, 0, 0, packet.width, packet.height, 10);
      }

      IrisShadowRuntimeResources resources;
      IOSurfaceRef directSurface = nullptr;
      id<MTLResidencySet> residency = nil;
      id<MTL4CommandAllocator> allocator = nil;
      id<MTL4CommandBuffer> commandBuffer = nil;
      MTL4RenderPassDescriptor *pass = nil;
      MTL4CommitOptions *options = nil;
      jlong status = -1;
      jlong unsupportedReason = 0;
      uint64_t outputHash = 0;
      bool submissionCommitted = false;
      std::shared_ptr<Metal4ProbeState> submissionState;
      std::vector<uint64_t> inputSurfaceLeases;
      @try {
        for (const auto &image : packet.buffers) {
          id<MTLBuffer> buffer = nil;
          if (image.storageKind == 2) {
            id<MTLBuffer> resident = iris_metal4_resident_buffer(
                image.sharedHandle, image.byteLength);
            if (resident)
              buffer = [resident retain];
            if (!buffer) {
              status = 0;
              unsupportedReason = 9;
              @throw [NSException
                  exceptionWithName:@"MetalRenderShadowUnsupported"
                             reason:@"resident buffer unavailable"
                           userInfo:nil];
            }
          } else {
            buffer = g_irisMetal4TransientBufferPool.acquire(
                (NSUInteger)image.byteLength);
            if (buffer)
              std::memcpy(buffer.contents, image.bytes.data(),
                          image.byteLength);
          }
          if (!buffer)
            @throw [NSException exceptionWithName:@"MetalRenderShadowSetup"
                                           reason:@"buffer allocation failed"
                                         userInfo:nil];
          resources.buffers.push_back(buffer);
          resources.pooledBufferOwnership.push_back(
              image.storageKind == 2 ? 0 : 1);
        }
        uint64_t sampledTextureBytes = 0;
        for (const auto &captured : packet.textures) {
          if (captured.storageKind == 2) {
            IrisMetal4InputHandoff *shared = iris_metal4_find_input_handoff(
                captured.glName, captured.sharedHandle);
            MTLPixelFormat expectedFormat =
                iris_metal_pixel_format(captured.format);
            if (!shared || shared->width != captured.width ||
                shared->height != captured.height ||
                !iris_input_handoff_format_compatible(
                    *shared, expectedFormat) ||
                !iris_metal4_track_input_surface_lease(
                    *shared, inputSurfaceLeases) ||
                !iris_shadow_add_bounded(sampledTextureBytes,
                    (uint64_t)captured.width * captured.height *
                        captured.bytesPerPixel,
                    kIrisShadowReplayMaximumTextureBytes)) {
              status = 0;
              unsupportedReason = 3;
              @throw [NSException
                  exceptionWithName:@"MetalRenderShadowUnsupported"
                               reason:@"shared texture unavailable"
                             userInfo:nil];
            }
            IrisMetal4InputHandoffReadiness readiness =
                iris_metal4_ensure_input_handoff_ready(*shared);
            if (readiness != IrisMetal4InputHandoffReadiness::READY) {
              status = 0;
              unsupportedReason = 3;
              @throw [NSException
                  exceptionWithName:@"MetalRenderShadowUnsupported"
                             reason:readiness ==
                                     IrisMetal4InputHandoffReadiness::FENCE_TIMEOUT
                                 ? @"shared texture fence timeout"
                                 : @"shared texture fence unavailable"
                           userInfo:nil];
            }
            id<MTLTexture> texture = [shared->metalTexture retain];
            resources.sampledTextures.push_back(texture);
            resources.sampledByName.emplace(captured.glName, texture);
            continue;
          }
          MTLPixelFormat format = MTLPixelFormatInvalid;
          const uint8_t *uploadBytes = nullptr;
          uint32_t uploadBytesPerPixel = 0;
          std::vector<uint8_t> expanded;
          if (captured.layer != 0 || captured.mipLevel != 0 ||
              !iris_shadow_texture_upload(captured, format, uploadBytes,
                                          uploadBytesPerPixel, expanded) ||
              !iris_shadow_add_bounded(sampledTextureBytes,
                  (uint64_t)captured.width * captured.height *
                      uploadBytesPerPixel,
                  kIrisShadowReplayMaximumTextureBytes)) {
            status = 0;
            unsupportedReason = 3;
            @throw [NSException exceptionWithName:@"MetalRenderShadowUnsupported"
                                           reason:@"texture format unsupported"
                                         userInfo:nil];
          }
          MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
              texture2DDescriptorWithPixelFormat:format
                                           width:captured.width
                                          height:captured.height
                                       mipmapped:NO];
          descriptor.storageMode = MTLStorageModeShared;
          descriptor.usage = MTLTextureUsageShaderRead;
          id<MTLTexture> texture = [g_device newTextureWithDescriptor:descriptor];
          if (!texture)
            @throw [NSException exceptionWithName:@"MetalRenderShadowSetup"
                                           reason:@"texture allocation failed"
                                         userInfo:nil];
          [texture replaceRegion:MTLRegionMake2D(0, 0, captured.width,
                                                 captured.height)
                    mipmapLevel:0 withBytes:uploadBytes
                    bytesPerRow:(NSUInteger)captured.width *
                                uploadBytesPerPixel];
          resources.sampledTextures.push_back(texture);
          resources.sampledByName.emplace(captured.glName, texture);
        }

        uint64_t targetBytes = 0;
        resources.colorTargets.resize(entry.colorFormats.size(), nil);
        for (size_t slot = 0; slot < entry.colorFormats.size(); slot++) {
          MTLPixelFormat format = entry.colorFormats[slot];
          if (format == MTLPixelFormatInvalid)
            continue;
          uint32_t bpp = 0;
          uint64_t bytes = 0;
          if (!iris_shadow_color_bytes(format, bpp) ||
              (bytes = (uint64_t)packet.width * packet.height * bpp) >
                  kIrisShadowReplayMaximumTextureBytes ||
              !iris_shadow_add_bounded(targetBytes, bytes,
                  kIrisShadowReplayMaximumTextureBytes)) {
            status = 0;
            unsupportedReason = 4;
            @throw [NSException exceptionWithName:@"MetalRenderShadowUnsupported"
                                           reason:@"target format unsupported"
                                         userInfo:nil];
          }
          MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
              texture2DDescriptorWithPixelFormat:format width:packet.width
                                          height:packet.height mipmapped:NO];
          descriptor.storageMode = MTLStorageModeShared;
          descriptor.usage = MTLTextureUsageRenderTarget |
                             MTLTextureUsageShaderRead;
          if (directPresentation && slot == 0) {
            size_t bytesPerRow = IOSurfaceAlignProperty(
                kIOSurfaceBytesPerRow, (size_t)packet.width * 4u);
            NSDictionary *surfaceProperties = @{
              (id)kIOSurfaceWidth : @(packet.width),
              (id)kIOSurfaceHeight : @(packet.height),
              (id)kIOSurfaceBytesPerElement : @4,
              (id)kIOSurfaceBytesPerRow : @(bytesPerRow),
              (id)kIOSurfaceAllocSize :
                  @(bytesPerRow * (size_t)packet.height),
              // BGRA is the CGL-supported 8-bit IOSurface declaration. The
              // Metal view intentionally remains RGBA8; the handoff shader
              // applies the corresponding R/B swizzle without a copy.
              (id)kIOSurfacePixelFormat : @((uint32_t)'BGRA'),
            };
            directSurface = IOSurfaceCreate(
                (__bridge CFDictionaryRef)surfaceProperties);
            if (directSurface) {
              resources.colorTargets[slot] =
                  [g_device newTextureWithDescriptor:descriptor
                                            iosurface:directSurface
                                                plane:0];
            }
          } else {
            resources.colorTargets[slot] =
                [g_device newTextureWithDescriptor:descriptor];
          }
          if (!resources.colorTargets[slot])
            @throw [NSException exceptionWithName:@"MetalRenderShadowSetup"
                                           reason:@"target allocation failed"
                                         userInfo:nil];
        }
        auto newDepthStencilTarget = [&](MTLPixelFormat format) {
          MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
              texture2DDescriptorWithPixelFormat:format width:packet.width
                                          height:packet.height mipmapped:NO];
          descriptor.storageMode = MTLStorageModePrivate;
          descriptor.usage = MTLTextureUsageRenderTarget;
          return [g_device newTextureWithDescriptor:descriptor];
        };
        if (entry.depthFormat != MTLPixelFormatInvalid) {
          resources.depthTarget = newDepthStencilTarget(entry.depthFormat);
          if (!resources.depthTarget)
            @throw [NSException exceptionWithName:@"MetalRenderShadowSetup"
                                           reason:@"depth allocation failed"
                                         userInfo:nil];
        }
        if (entry.stencilFormat != MTLPixelFormatInvalid) {
          if (entry.stencilFormat == entry.depthFormat) {
            resources.stencilTarget = resources.depthTarget;
          } else {
            resources.stencilTarget =
                newDepthStencilTarget(entry.stencilFormat);
            if (!resources.stencilTarget)
              @throw [NSException exceptionWithName:@"MetalRenderShadowSetup"
                                             reason:@"stencil allocation failed"
                                           userInfo:nil];
          }
        }

        for (const auto &stage : packet.stages) {
          int prepared = iris_shadow_prepare_arguments(stage,
              stage.stage == 0 ? entry.vertexFunction : entry.fragmentFunction,
              resources);
          if (prepared <= 0) {
            status = prepared;
            if (prepared == 0) unsupportedReason = 5;
            @throw [NSException exceptionWithName:
                       prepared == 0 ? @"MetalRenderShadowUnsupported"
                                     : @"MetalRenderShadowSetup"
                                           reason:@"argument setup failed"
                                         userInfo:nil];
          }
        }
        bool needVertexTable = !packet.vertexBuffers.empty();
        if (needVertexTable && !resources.vertexTable) {
          MTL4ArgumentTableDescriptor *descriptor =
              [[MTL4ArgumentTableDescriptor alloc] init];
          descriptor.maxBufferBindCount = 31;
          descriptor.initializeBindings = YES;
          NSError *error = nil;
          resources.vertexTable =
              [g_device newArgumentTableWithDescriptor:descriptor error:&error];
          [descriptor release];
          if (!resources.vertexTable)
            @throw [NSException exceptionWithName:@"MetalRenderShadowSetup"
                                           reason:@"vertex table failed"
                                         userInfo:nil];
        }
        for (const auto &binding : packet.vertexBuffers) {
          [resources.vertexTable
              setAddress:resources.buffers[binding.second].gpuAddress
                 atIndex:binding.first];
        }

        NSUInteger allocationCount = resources.buffers.size() +
            resources.inlineBuffers.size() + resources.argumentBuffers.size() +
            resources.sampledTextures.size() + resources.colorTargets.size() +
            (resources.depthTarget ? 1 : 0) +
            (resources.stencilTarget &&
             resources.stencilTarget != resources.depthTarget ? 1 : 0);
        MTLResidencySetDescriptor *residencyDescriptor =
            [[MTLResidencySetDescriptor alloc] init];
        residencyDescriptor.label = @"MetalRender Iris packet replay";
        residencyDescriptor.initialCapacity = std::max((NSUInteger)1,
                                                       allocationCount);
        NSError *residencyError = nil;
        residency = [g_device
            newResidencySetWithDescriptor:residencyDescriptor
                                    error:&residencyError];
        [residencyDescriptor release];
        if (!residency)
          @throw [NSException exceptionWithName:@"MetalRenderShadowSetup"
                                         reason:@"residency allocation failed"
                                       userInfo:nil];
        for (id<MTLBuffer> value : resources.buffers)
          [residency addAllocation:(id<MTLAllocation>)value];
        for (id<MTLBuffer> value : resources.inlineBuffers)
          [residency addAllocation:(id<MTLAllocation>)value];
        for (id<MTLBuffer> value : resources.argumentBuffers)
          [residency addAllocation:(id<MTLAllocation>)value];
        for (id<MTLTexture> value : resources.sampledTextures)
          [residency addAllocation:(id<MTLAllocation>)value];
        for (id<MTLTexture> value : resources.colorTargets) {
          if (value) [residency addAllocation:(id<MTLAllocation>)value];
        }
        if (resources.depthTarget)
          [residency addAllocation:(id<MTLAllocation>)resources.depthTarget];
        if (resources.stencilTarget &&
            resources.stencilTarget != resources.depthTarget)
          [residency addAllocation:(id<MTLAllocation>)resources.stencilTarget];
        [residency commit];
        [residency requestResidency];

        allocator = [g_device newCommandAllocator];
        commandBuffer = [g_device newCommandBuffer];
        if (!allocator || !commandBuffer)
          @throw [NSException exceptionWithName:@"MetalRenderShadowSetup"
                                         reason:@"command allocation failed"
                                       userInfo:nil];
        [commandBuffer beginCommandBufferWithAllocator:allocator];
        [commandBuffer useResidencySet:residency];
        pass = [[MTL4RenderPassDescriptor alloc] init];
        pass.defaultRasterSampleCount = 1;
        pass.renderTargetWidth = packet.width;
        pass.renderTargetHeight = packet.height;
        for (size_t slot = 0; slot < resources.colorTargets.size(); slot++) {
          if (!resources.colorTargets[slot])
            continue;
          MTLRenderPassColorAttachmentDescriptor *color =
              pass.colorAttachments[slot];
          color.texture = resources.colorTargets[slot];
          color.loadAction = MTLLoadActionClear;
          color.storeAction = MTLStoreActionStore;
          color.clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 0.0);
        }
        if (resources.depthTarget) {
          pass.depthAttachment.texture = resources.depthTarget;
          pass.depthAttachment.loadAction = MTLLoadActionClear;
          pass.depthAttachment.storeAction = MTLStoreActionDontCare;
          pass.depthAttachment.clearDepth = 1.0;
        }
        if (resources.stencilTarget) {
          pass.stencilAttachment.texture = resources.stencilTarget;
          pass.stencilAttachment.loadAction = MTLLoadActionClear;
          pass.stencilAttachment.storeAction = MTLStoreActionDontCare;
          pass.stencilAttachment.clearStencil = 0;
        }
        id<MTL4RenderCommandEncoder> encoder =
            [commandBuffer renderCommandEncoderWithDescriptor:pass];
        if (!encoder)
          @throw [NSException exceptionWithName:@"MetalRenderShadowSetup"
                                         reason:@"render encoder unavailable"
                                       userInfo:nil];
        [encoder setRenderPipelineState:entry.render];
        [encoder setDepthStencilState:entry.depthStencil];
        [encoder setCullMode:entry.cullMode];
        [encoder setFrontFacingWinding:entry.frontFacingWinding];
        [encoder setTriangleFillMode:entry.triangleFillMode];
        [encoder setDepthClipMode:entry.depthClipMode];
        uint32_t offsetBit = entry.topology == 0 ? 1u
            : (entry.topology >= 1 && entry.topology <= 3 ? 2u : 4u);
        if ((entry.polygonOffsetMask & offsetBit) != 0) {
          [encoder setDepthBias:entry.depthBias slopeScale:entry.slopeScale
                          clamp:entry.depthBiasClamp];
        }
        [encoder setStencilFrontReferenceValue:entry.frontStencilReference
                            backReferenceValue:entry.backStencilReference];
        MTLViewport viewport = {(double)packet.viewport.x,
            (double)((int64_t)packet.height - packet.viewport.y -
                     packet.viewport.height),
            (double)packet.viewport.width, (double)packet.viewport.height,
            0.0, 1.0};
        [encoder setViewport:viewport];
        int64_t sx0 = packet.scissorEnabled
            ? std::max<int64_t>(0, packet.scissor.x) : 0;
        int64_t sy0 = packet.scissorEnabled
            ? std::max<int64_t>(0, packet.scissor.y) : 0;
        int64_t sx1 = packet.scissorEnabled
            ? std::min<int64_t>(packet.width,
                (int64_t)packet.scissor.x + packet.scissor.width)
            : packet.width;
        int64_t sy1 = packet.scissorEnabled
            ? std::min<int64_t>(packet.height,
                (int64_t)packet.scissor.y + packet.scissor.height)
            : packet.height;
        if (sx1 <= sx0 || sy1 <= sy0) {
          [encoder endEncoding];
        } else {
          MTLScissorRect scissor = {(NSUInteger)sx0,
              (NSUInteger)((int64_t)packet.height - sy1),
              (NSUInteger)(sx1 - sx0), (NSUInteger)(sy1 - sy0)};
          [encoder setScissorRect:scissor];
          if (resources.vertexTable)
            [encoder setArgumentTable:resources.vertexTable
                              atStages:MTLRenderStageVertex];
          if (resources.fragmentTable)
            [encoder setArgumentTable:resources.fragmentTable
                              atStages:MTLRenderStageFragment];
          if (packet.draw.kind == 1) {
            [encoder drawPrimitives:primitiveType
                         vertexStart:(NSUInteger)packet.draw.firstVertex
                         vertexCount:packet.draw.vertexCount
                       instanceCount:packet.draw.instanceCount
                        baseInstance:packet.draw.baseInstance];
          } else {
            id<MTLBuffer> indices =
                resources.buffers[(size_t)packet.indexBufferImage];
            MTLIndexType indexType = packet.draw.indexElementBytes == 2
                ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32;
            for (const auto &draw : packet.draw.indexed) {
              uint64_t required = (uint64_t)draw.count *
                                  packet.draw.indexElementBytes;
              if (draw.offset % packet.draw.indexElementBytes != 0 ||
                  draw.offset > indices.length ||
                  required > indices.length - draw.offset) {
                [encoder endEncoding];
                status = 0;
                unsupportedReason = 6;
                @throw [NSException
                    exceptionWithName:@"MetalRenderShadowUnsupported"
                               reason:@"index range unsupported"
                             userInfo:nil];
              }
              [encoder drawIndexedPrimitives:primitiveType
                                  indexCount:draw.count
                                   indexType:indexType
                                 indexBuffer:indices.gpuAddress + draw.offset
                           indexBufferLength:indices.length - draw.offset
                               instanceCount:packet.draw.instanceCount
                                  baseVertex:draw.baseVertex
                                baseInstance:packet.draw.baseInstance];
            }
          }
          [encoder endEncoding];
        }
        [commandBuffer endCommandBuffer];
        g_irisMetal4PipelineDrawAttemptCount.fetch_add(
            1, std::memory_order_relaxed);
        submissionState = std::make_shared<Metal4ProbeState>(allocator);
        submissionCommitted = true;
        bool completed = metal4_commit_and_wait(
            (id<MTL4CommandQueue>)g_metal4CommandQueue,
            commandBuffer, submissionState, 5'000);
        if (!completed ||
            !submissionState->completed.load(std::memory_order_acquire) ||
            !submissionState->succeeded.load(std::memory_order_acquire)) {
          status = -1;
        } else {
          id<MTLTexture> output = nil;
          for (id<MTLTexture> candidate : resources.colorTargets) {
            if (candidate) { output = candidate; break; }
          }
          uint32_t bpp = 0;
          if (!output || !iris_shadow_color_bytes(output.pixelFormat, bpp)) {
            status = 0;
            unsupportedReason = 7;
          } else if (directPresentation) {
            if (!directSurface ||
                output.pixelFormat != MTLPixelFormatRGBA8Unorm) {
              status = 0;
              unsupportedReason = 8;
            } else {
              uint64_t token = g_irisMetal4CutoverSurfaceSequence.fetch_add(
                  1, std::memory_order_relaxed);
              if (token == 0) {
                token = g_irisMetal4CutoverSurfaceSequence.fetch_add(
                    1, std::memory_order_relaxed);
              }
              g_irisMetal4PendingCutoverSurface = directSurface;
              g_irisMetal4PendingCutoverWidth = packet.width;
              g_irisMetal4PendingCutoverHeight = packet.height;
              directSurface = nullptr;
              outputHash = token;
              status = 1;
            }
          } else {
            size_t rowBytes = (size_t)packet.width * bpp;
            std::vector<uint8_t> pixels(rowBytes * packet.height);
            [output getBytes:pixels.data() bytesPerRow:rowBytes
                  fromRegion:MTLRegionMake2D(0, 0, packet.width, packet.height)
                 mipmapLevel:0];
            outputHash = iris_shadow_fnv1a64(pixels.data(), pixels.size());
            if (output.pixelFormat == MTLPixelFormatRGBA8Unorm) {
              g_irisMetal4LastReplayRgba8 = pixels;
            }
            status = 1;
          }
        }
      } @catch (NSException *exception) {
        if (![exception.name isEqualToString:@"MetalRenderShadowUnsupported"])
          dbg("WARN: Iris packet replay raised %s: %s\n",
              exception.name.UTF8String ?: "NSException",
              exception.reason.UTF8String ?: "unknown reason");
      }
      bool retired = submissionCommitted &&
          iris_metal4_retire_replay_submission(resources, entry, residency,
              allocator, commandBuffer, pass, options, submissionState);
      if (options) [options release];
      if (pass) [pass release];
      if (commandBuffer) [commandBuffer release];
      if (allocator) [allocator release];
      if (residency) {
        [residency endResidency];
        [residency release];
      }
      if (directSurface) CFRelease(directSurface);
      if (!retired)
        releaseEntry();
      iris_metal4_finish_input_surface_leases(inputSurfaceLeases,
          submissionCommitted ? submissionState : nullptr);
      return iris_shadow_result(env, status, (jlong)outputHash,
                                packet.width, packet.height,
                                unsupportedReason);
    }
    return iris_shadow_result(env, 0, 0, 0, 0, 1);
  }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nRunIrisMetal4ShadowReplay(
    JNIEnv *env, jclass, jstring pipelineKeyValue, jbyteArray packetValue) {
  return run_iris_metal4_replay(env, pipelineKeyValue, packetValue, false);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nRunIrisMetal4FinalCutoverReplay(
    JNIEnv *env, jclass, jstring pipelineKeyValue, jbyteArray packetValue) {
  return run_iris_metal4_replay(env, pipelineKeyValue, packetValue, true);
}

static void destroy_iris_metal4_input_handoff(
    IrisMetal4InputHandoff &handoff, bool hasContext) {
  if (hasContext && handoff.copyFence) {
    glDeleteSync(handoff.copyFence);
  }
  if (hasContext && handoff.readFramebuffer) {
    glDeleteFramebuffers(1, &handoff.readFramebuffer);
  }
  if (hasContext && handoff.drawFramebuffer) {
    glDeleteFramebuffers(1, &handoff.drawFramebuffer);
  }
  if (hasContext && handoff.rectangleTexture) {
    glDeleteTextures(1, &handoff.rectangleTexture);
  }
  if (handoff.metalTexture) {
    [handoff.metalTexture release];
  }
  if (handoff.surface) {
    CFRelease(handoff.surface);
  }
  handoff = IrisMetal4InputHandoff{};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadIrisMetal4InputTexture(
    JNIEnv *env, jclass, jint glTexture, jlong generation, jint width,
    jint height, jbyteArray rgba8Value) {
  std::lock_guard<std::mutex> executionLock(g_irisMetal4ExecutionMutex);
  @autoreleasepool {
    if (!g_device || !g_metal4CommandQueue || glTexture <= 0 ||
        generation <= 0 || width <= 0 || height <= 0 || width > 4096 ||
        height > 4096 || !rgba8Value) {
      return 0;
    }
    uint64_t expected = (uint64_t)width * height * 4u;
    if (expected == 0 || expected > kIrisShadowReplayMaximumTextureBytes ||
        expected > (uint64_t)std::numeric_limits<jsize>::max() ||
        env->GetArrayLength(rgba8Value) != (jsize)expected) {
      return 0;
    }

    GLuint sourceTexture = (GLuint)glTexture;
    auto existing = g_irisMetal4InputHandoffs.find(sourceTexture);
    if (existing != g_irisMetal4InputHandoffs.end() &&
        existing->second.kind == 3 &&
        existing->second.sourceGeneration == (uint64_t)generation &&
        existing->second.width == (uint32_t)width &&
        existing->second.height == (uint32_t)height &&
        existing->second.metalTexture) {
      return (jlong)existing->second.token;
    }
    if (existing != g_irisMetal4InputHandoffs.end()) {
      bool hasContext = CGLGetCurrentContext() != nullptr;
      if (hasContext && (existing->second.surface ||
                         existing->second.copyFence)) {
        glFinish();
      }
      uint64_t allocationBytes = existing->second.allocationBytes;
      destroy_iris_metal4_input_handoff(existing->second, hasContext);
      g_irisMetal4InputHandoffs.erase(existing);
      g_irisMetal4InputHandoffBytes =
          allocationBytes <= g_irisMetal4InputHandoffBytes
              ? g_irisMetal4InputHandoffBytes - allocationBytes : 0;
    }
    if (g_irisMetal4InputHandoffs.size() >=
            kIrisMetal4InputHandoffLimit ||
        expected > kIrisMetal4InputHandoffByteLimit ||
        g_irisMetal4InputHandoffBytes >
            kIrisMetal4InputHandoffByteLimit - expected) {
      return 0;
    }

    std::vector<jbyte> bytes;
    try {
      bytes.resize((size_t)expected);
    } catch (...) {
      return 0;
    }
    env->GetByteArrayRegion(rgba8Value, 0, (jsize)expected, bytes.data());
    if (env->ExceptionCheck()) {
      return 0;
    }
    MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                     width:(NSUInteger)width
                                    height:(NSUInteger)height
                                 mipmapped:NO];
    descriptor.storageMode = MTLStorageModeShared;
    descriptor.usage = MTLTextureUsageShaderRead;
    id<MTLTexture> texture = [g_device newTextureWithDescriptor:descriptor];
    if (!texture) {
      return 0;
    }
    [texture replaceRegion:MTLRegionMake2D(0, 0, width, height)
               mipmapLevel:0 withBytes:bytes.data()
               bytesPerRow:(NSUInteger)width * 4u];

    IrisMetal4InputHandoff handoff;
    handoff.kind = 3;
    handoff.sourceTexture = sourceTexture;
    handoff.sourceGeneration = (uint64_t)generation;
    handoff.allocationBytes = expected;
    handoff.width = (uint32_t)width;
    handoff.height = (uint32_t)height;
    handoff.metalTexture = texture;
    handoff.copyReady = true;
    handoff.token = g_irisMetal4InputHandoffSequence.fetch_add(
        1, std::memory_order_relaxed);
    if (handoff.token == 0) {
      handoff.token = g_irisMetal4InputHandoffSequence.fetch_add(
          1, std::memory_order_relaxed);
    }
    uint64_t token = handoff.token;
    g_irisMetal4InputHandoffs.emplace(sourceTexture, handoff);
    g_irisMetal4InputHandoffBytes += expected;
    return (jlong)token;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadIrisMetal4InputBuffer(
    JNIEnv *env, jclass, jstring digestValue, jbyteArray bytesValue) {
  std::lock_guard<std::mutex> executionLock(g_irisMetal4ExecutionMutex);
  @autoreleasepool {
    if (!g_device || !g_metal4CommandQueue ||
        !g_metal4RuntimeVerified.load(std::memory_order_acquire) ||
        !digestValue || !bytesValue) {
      return 0;
    }
    const char *digestUtf8 = env->GetStringUTFChars(digestValue, nullptr);
    if (!digestUtf8) {
      return 0;
    }
    bool validDigest = valid_iris_pipeline_key(digestUtf8);
    std::string digest = validDigest ? std::string(digestUtf8)
                                     : std::string();
    env->ReleaseStringUTFChars(digestValue, digestUtf8);
    jsize length = env->GetArrayLength(bytesValue);
    if (!validDigest || length <= 0 ||
        (uint64_t)length > kIrisShadowReplayMaximumBufferBytes) {
      return 0;
    }
    auto existing = g_irisMetal4ResidentBuffers.find(digest);
    if (existing != g_irisMetal4ResidentBuffers.end()) {
      return existing->second.byteLength == (uint32_t)length &&
                     existing->second.buffer
                 ? (jlong)existing->second.token
                 : 0;
    }
    if ((uint64_t)length > kIrisMetal4ResidentBufferByteLimit ||
        g_irisMetal4ResidentBuffers.size() >=
            kIrisMetal4ResidentBufferLimit ||
        g_irisMetal4ResidentBufferBytes >
            kIrisMetal4ResidentBufferByteLimit - (uint64_t)length) {
      return 0;
    }
    id<MTLBuffer> buffer = [g_device
        newBufferWithLength:(NSUInteger)length
                    options:MTLResourceStorageModeShared];
    if (!buffer) {
      return 0;
    }
    env->GetByteArrayRegion(bytesValue, 0, length,
                            reinterpret_cast<jbyte *>(buffer.contents));
    if (env->ExceptionCheck()) {
      [buffer release];
      return 0;
    }
    IrisMetal4ResidentBuffer resident;
    resident.byteLength = (uint32_t)length;
    resident.buffer = buffer;
    resident.token = g_irisMetal4ResidentBufferSequence.fetch_add(
        1, std::memory_order_relaxed);
    if (resident.token == 0) {
      resident.token = g_irisMetal4ResidentBufferSequence.fetch_add(
          1, std::memory_order_relaxed);
    }
    uint64_t token = resident.token;
    g_irisMetal4ResidentBuffers.emplace(digest, resident);
    g_irisMetal4ResidentBuffersByToken.emplace(token, resident);
    g_irisMetal4ResidentBufferBytes += (uint64_t)length;
    return (jlong)token;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nCaptureIrisMetal4InputSurface(
    JNIEnv *, jclass, jint glTexture, jint width, jint height, jint kind) {
  std::lock_guard<std::mutex> executionLock(g_irisMetal4ExecutionMutex);
  @autoreleasepool {
    CGLContextObj context = CGLGetCurrentContext();
    if (!context || !g_device || !g_metal4CommandQueue || glTexture <= 0 ||
        width <= 0 || height <= 0 || width > 4096 || height > 4096 ||
        (kind != 1 && kind != 2)) {
      return -1;
    }

    GLuint sourceTexture = (GLuint)glTexture;
    uint64_t captureSerial =
        ++g_irisMetal4InputSurfaceCaptureSequences[sourceTexture];
    if (captureSerial == 0) {
      captureSerial = 1;
      g_irisMetal4InputSurfaceCaptureSequences[sourceTexture] =
          captureSerial;
    }

    IrisMetal4InputHandoff *selected = nullptr;
    for (auto iterator = g_irisMetal4InputSurfaceHandoffs.begin();
         iterator != g_irisMetal4InputSurfaceHandoffs.end();) {
      IrisMetal4InputHandoff &candidate = iterator->second;
      bool feedbackCompleted = false;
      if (candidate.inFlightFeedback) {
        if (@available(macOS 26.0, *)) {
          feedbackCompleted = iris_metal4_submission_completed(
              candidate.inFlightFeedback);
        }
      }
      if (feedbackCompleted) {
        candidate.inFlightFeedback.reset();
      }
      // Never reclaim a lease merely because it is old: the translator can
      // be delayed independently of the render thread. Abandoned leases are
      // bounded by the hard slot/byte limits and lifecycle reset, preserving
      // fail-open correctness under worker stalls.
      bool idle = !candidate.leased && !candidate.inFlightFeedback;
      bool compatible = candidate.sourceTexture == sourceTexture &&
          candidate.kind == (uint32_t)kind &&
          candidate.width == (uint32_t)width &&
          candidate.height == (uint32_t)height;
      if (idle && candidate.sourceTexture == sourceTexture &&
          !compatible) {
        uint64_t bytes = candidate.allocationBytes;
        destroy_iris_metal4_input_handoff(candidate, true);
        iterator = g_irisMetal4InputSurfaceHandoffs.erase(iterator);
        g_irisMetal4InputSurfaceBytes =
            bytes <= g_irisMetal4InputSurfaceBytes
                ? g_irisMetal4InputSurfaceBytes - bytes : 0;
        continue;
      }
      if (!selected && idle && compatible) {
        selected = &candidate;
      }
      ++iterator;
    }

    size_t bytesPerElement = kind == 1 ? 4u : 8u;
    size_t bytesPerRow = IOSurfaceAlignProperty(
        kIOSurfaceBytesPerRow, (size_t)width * bytesPerElement);
    if (bytesPerRow == 0 || (size_t)height > SIZE_MAX / bytesPerRow) {
      return -2;
    }
    uint64_t allocationBytes = (uint64_t)bytesPerRow * (uint64_t)height;

    if (!selected) {
      // Reclaim only completed, unleased slots. Busy surfaces are immutable
      // until their Metal feedback reports completion.
      while ((g_irisMetal4InputSurfaceHandoffs.size() >=
                  kIrisMetal4InputSurfaceHandoffLimit ||
              allocationBytes > kIrisMetal4InputSurfaceByteLimit ||
              g_irisMetal4InputSurfaceBytes >
                  kIrisMetal4InputSurfaceByteLimit - allocationBytes)) {
        auto recyclable = std::find_if(
            g_irisMetal4InputSurfaceHandoffs.begin(),
            g_irisMetal4InputSurfaceHandoffs.end(),
            [](const auto &entry) {
              return !entry.second.leased &&
                  !entry.second.inFlightFeedback;
            });
        if (recyclable == g_irisMetal4InputSurfaceHandoffs.end()) {
          return -3;
        }
        uint64_t bytes = recyclable->second.allocationBytes;
        destroy_iris_metal4_input_handoff(recyclable->second, true);
        g_irisMetal4InputSurfaceHandoffs.erase(recyclable);
        g_irisMetal4InputSurfaceBytes =
            bytes <= g_irisMetal4InputSurfaceBytes
                ? g_irisMetal4InputSurfaceBytes - bytes : 0;
      }
      IrisMetal4InputHandoff handoff;
      handoff.kind = (uint32_t)kind;
      handoff.width = (uint32_t)width;
      handoff.height = (uint32_t)height;
      handoff.sourceTexture = sourceTexture;
      handoff.captureSerial = captureSerial;
      handoff.allocationBytes = allocationBytes;
      handoff.token = g_irisMetal4InputHandoffSequence.fetch_add(
          1, std::memory_order_relaxed);
      if (handoff.token == 0) {
        handoff.token = g_irisMetal4InputHandoffSequence.fetch_add(
            1, std::memory_order_relaxed);
      }

      uint32_t pixelFourcc = kind == 1 ? (uint32_t)'BGRA'
                                      : (uint32_t)'RGhA';
      NSDictionary *surfaceProperties = @{
        (id)kIOSurfaceWidth : @(width),
        (id)kIOSurfaceHeight : @(height),
        (id)kIOSurfaceBytesPerElement : @(bytesPerElement),
        (id)kIOSurfaceBytesPerRow : @(bytesPerRow),
        (id)kIOSurfaceAllocSize : @(bytesPerRow * (size_t)height),
        (id)kIOSurfacePixelFormat : @(pixelFourcc),
      };
      handoff.surface = IOSurfaceCreate(
          (__bridge CFDictionaryRef)surfaceProperties);
      if (!handoff.surface) {
        return -4;
      }

      MTLPixelFormat metalFormat = kind == 1
          ? MTLPixelFormatBGRA8Unorm : MTLPixelFormatRGBA16Float;
      MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
          texture2DDescriptorWithPixelFormat:metalFormat
                                       width:(NSUInteger)width
                                      height:(NSUInteger)height
                                   mipmapped:NO];
      descriptor.storageMode = MTLStorageModeShared;
      descriptor.usage = MTLTextureUsageShaderRead;
      handoff.metalTexture = [g_device newTextureWithDescriptor:descriptor
                                                       iosurface:handoff.surface
                                                           plane:0];
      if (!handoff.metalTexture) {
        destroy_iris_metal4_input_handoff(handoff, true);
        return -5;
      }

      GLint previousRectangleTexture = 0;
      glGetIntegerv(GL_TEXTURE_BINDING_RECTANGLE,
                    &previousRectangleTexture);
      glGenTextures(1, &handoff.rectangleTexture);
      glBindTexture(GL_TEXTURE_RECTANGLE, handoff.rectangleTexture);
      GLenum internalFormat = kind == 1 ? GL_RGBA8 : GL_RGBA16F;
      GLenum externalFormat = kind == 1 ? GL_BGRA : GL_RGBA;
      GLenum componentType = kind == 1
          ? GL_UNSIGNED_INT_8_8_8_8_REV : GL_HALF_FLOAT;
      CGLError bindError = CGLTexImageIOSurface2D(
          context, GL_TEXTURE_RECTANGLE, internalFormat, width, height,
          externalFormat, componentType, handoff.surface, 0);
      if (bindError != kCGLNoError) {
        glBindTexture(GL_TEXTURE_RECTANGLE,
                      (GLuint)previousRectangleTexture);
        destroy_iris_metal4_input_handoff(handoff, true);
        return -6;
      }
      glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
      glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
      glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_S,
                      GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_T,
                      GL_CLAMP_TO_EDGE);
      glBindTexture(GL_TEXTURE_RECTANGLE,
                    (GLuint)previousRectangleTexture);
      glGenFramebuffers(1, &handoff.readFramebuffer);
      glGenFramebuffers(1, &handoff.drawFramebuffer);
      uint64_t token = handoff.token;
      auto inserted = g_irisMetal4InputSurfaceHandoffs.emplace(
          token, std::move(handoff));
      if (!inserted.second) {
        destroy_iris_metal4_input_handoff(handoff, true);
        return -7;
      }
      g_irisMetal4InputSurfaceBytes += allocationBytes;
      selected = &inserted.first->second;
    }

    IrisMetal4InputHandoff &handoff = *selected;
    handoff.captureSerial = captureSerial;
    handoff.leased = true;
    if (handoff.copyFence) {
      IrisMetal4InputHandoffReadiness priorCopy =
          iris_metal4_ensure_input_handoff_ready(handoff);
      if (priorCopy != IrisMetal4InputHandoffReadiness::READY) {
        handoff.leased = false;
        return priorCopy == IrisMetal4InputHandoffReadiness::MISSING_FENCE
            ? -8 : -9;
      }
    }
    GLint previousReadFramebuffer = 0;
    GLint previousDrawFramebuffer = 0;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &previousReadFramebuffer);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &previousDrawFramebuffer);
    GLboolean scissorEnabled = glIsEnabled(GL_SCISSOR_TEST);
    bool copied = false;
    handoff.copyReady = false;
    glBindFramebuffer(GL_READ_FRAMEBUFFER, handoff.readFramebuffer);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, sourceTexture, 0);
    glReadBuffer(GL_COLOR_ATTACHMENT0);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, handoff.drawFramebuffer);
    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_RECTANGLE,
                           handoff.rectangleTexture, 0);
    glDrawBuffer(GL_COLOR_ATTACHMENT0);
    if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) ==
            GL_FRAMEBUFFER_COMPLETE &&
        glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) ==
            GL_FRAMEBUFFER_COMPLETE) {
      glDisable(GL_SCISSOR_TEST);
      // Convert into the exact CGL-compatible surface format. Keep row order
      // identical to glGetTexImage + replaceRegion: Metal's texture-coordinate
      // translation already preserves the OpenGL convention, so flipping the
      // IOSurface here would apply the Y conversion twice.
      glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                        GL_COLOR_BUFFER_BIT, GL_NEAREST);
      handoff.copyFence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
      if (handoff.copyFence) {
        glFlush();
        copied = true;
      }
    }
    if (scissorEnabled) glEnable(GL_SCISSOR_TEST);
    else glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER,
                      (GLuint)previousReadFramebuffer);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER,
                      (GLuint)previousDrawFramebuffer);
    if (!copied) {
      handoff.leased = false;
      return -10;
    }
    IrisMetal4InputHandoffReadiness copiedReadiness =
        iris_metal4_ensure_input_handoff_ready(handoff);
    if (copiedReadiness != IrisMetal4InputHandoffReadiness::READY) {
      handoff.leased = false;
      return copiedReadiness == IrisMetal4InputHandoffReadiness::MISSING_FENCE
          ? -11 : -12;
    }
    return (jlong)handoff.token;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nReleaseIrisMetal4InputSurface(
    JNIEnv *, jclass, jlong handle) {
  std::lock_guard<std::mutex> executionLock(g_irisMetal4ExecutionMutex);
  if (handle <= 0) {
    return JNI_FALSE;
  }
  auto found = g_irisMetal4InputSurfaceHandoffs.find((uint64_t)handle);
  if (found == g_irisMetal4InputSurfaceHandoffs.end()) {
    // Immutable resident handles live in a different table and must survive.
    return JNI_FALSE;
  }
  IrisMetal4InputHandoff &handoff = found->second;
  if (handoff.inFlightFeedback) {
    // A committed packet owns the surface until Metal completion feedback.
    // Synchronous validation can finish before Java closes its frame lease;
    // observe that already-signalled fence here instead of requiring another
    // capture call to reap it.
    if (@available(macOS 26.0, *)) {
      if (!iris_metal4_submission_completed(handoff.inFlightFeedback)) {
        return JNI_FALSE;
      }
      handoff.inFlightFeedback.reset();
    } else {
      return JNI_FALSE;
    }
  }
  handoff.leased = false;
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBindIrisMetal4FinalCutoverSurface(
    JNIEnv *, jclass, jint glTexture, jint width, jint height,
    jboolean deferIfBusy) {
  std::unique_lock<std::mutex> executionLock(g_irisMetal4ExecutionMutex,
      std::defer_lock);
  if (deferIfBusy == JNI_TRUE) {
    executionLock.try_lock();
  } else {
    executionLock.lock();
  }
  if (!executionLock.owns_lock()) {
    // The promoted IOSurface remains pending and this bind is retried. The
    // caller can present its last fenced surface during worker contention.
    return JNI_FALSE;
  }
  @autoreleasepool {
    CGLContextObj context = CGLGetCurrentContext();
    if (!context || glTexture <= 0 || width <= 0 || height <= 0 ||
        !g_irisMetal4PendingCutoverSurface ||
        g_irisMetal4PendingCutoverWidth != (uint32_t)width ||
        g_irisMetal4PendingCutoverHeight != (uint32_t)height) {
      return JNI_FALSE;
    }

    GLuint texture = (GLuint)glTexture;
    auto existing = g_irisMetal4CutoverGlBindings.find(texture);
    if (existing == g_irisMetal4CutoverGlBindings.end()) {
      if (g_irisMetal4CutoverGlBindings.size() >=
          kIrisMetal4CutoverBindingLimit) {
        return JNI_FALSE;
      }
      existing = g_irisMetal4CutoverGlBindings.emplace(
          texture, IrisMetal4CutoverGlBinding{}).first;
    }
    IrisMetal4CutoverGlBinding &binding = existing->second;
    if (binding.completionFence) {
      GLenum fenceStatus = glClientWaitSync(binding.completionFence, 0, 0);
      if (fenceStatus != GL_ALREADY_SIGNALED &&
          fenceStatus != GL_CONDITION_SATISFIED) {
        return JNI_FALSE;
      }
      glDeleteSync(binding.completionFence);
      binding.completionFence = nullptr;
    } else if (binding.surface) {
      // A surface without a completion fence cannot be proven idle. Retire
      // this slot until renderer lifecycle cleanup rather than blocking or
      // risking a use-after-release in the GL command stream.
      return JNI_FALSE;
    }
    IOSurfaceRef previousSurface = binding.surface;
    uint32_t previousWidth = binding.width;
    uint32_t previousHeight = binding.height;
    binding.surface = nullptr;
    binding.width = 0;
    binding.height = 0;

    // The Java presenter binds glTexture on GL_TEXTURE0 immediately before
    // entering JNI. Avoid querying and rebinding the same GL state here on
    // every frame; CGLTexImageIOSurface2D operates on that current binding.
    CGLError error = CGLTexImageIOSurface2D(
        context, GL_TEXTURE_RECTANGLE, GL_RGBA8,
        (GLsizei)width, (GLsizei)height, GL_BGRA,
        GL_UNSIGNED_INT_8_8_8_8_REV,
        g_irisMetal4PendingCutoverSurface, 0);
    if (error != kCGLNoError) {
      binding.surface = previousSurface;
      binding.width = previousWidth;
      binding.height = previousHeight;
      return JNI_FALSE;
    }

    if (previousSurface && !iris_metal4_recycle_graph_surface(
            previousSurface, previousWidth, previousHeight)) {
      CFRelease(previousSurface);
    }

    binding.surface = g_irisMetal4PendingCutoverSurface;
    binding.width = (uint32_t)width;
    binding.height = (uint32_t)height;
    g_irisMetal4PendingCutoverSurface = nullptr;
    g_irisMetal4PendingCutoverWidth = 0;
    g_irisMetal4PendingCutoverHeight = 0;
    g_metal4DrawPathActive.store(true, std::memory_order_release);
    return JNI_TRUE;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nFenceIrisMetal4FinalCutoverSurface(
    JNIEnv *, jclass, jint glTexture) {
  CGLContextObj context = CGLGetCurrentContext();
  auto existing = g_irisMetal4CutoverGlBindings.find((GLuint)glTexture);
  if (!context || glTexture <= 0 ||
      existing == g_irisMetal4CutoverGlBindings.end() ||
      !existing->second.surface) {
    return JNI_FALSE;
  }
  if (existing->second.completionFence) {
    // Refresh the fence after every read of a reusable ownership surface. GL
    // keeps the deleted sync alive until earlier commands retire; the newest
    // fence is therefore the exact release boundary for a future rebind.
    glDeleteSync(existing->second.completionFence);
    existing->second.completionFence = nullptr;
  }
  GLsync fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
  if (!fence) {
    return JNI_FALSE;
  }
  existing->second.completionFence = fence;
  // Submit the blit without waiting. A later reuse probes the fence with a
  // zero timeout; a busy slot simply leaves that frame on OpenGL.
  glFlush();
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDiscardIrisMetal4FinalCutoverSurface(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> executionLock(g_irisMetal4ExecutionMutex);
  if (!g_irisMetal4PendingCutoverSurface)
    return JNI_FALSE;
  if (!iris_metal4_recycle_graph_surface(
          g_irisMetal4PendingCutoverSurface,
          g_irisMetal4PendingCutoverWidth,
          g_irisMetal4PendingCutoverHeight)) {
    CFRelease(g_irisMetal4PendingCutoverSurface);
  }
  g_irisMetal4PendingCutoverSurface = nullptr;
  g_irisMetal4PendingCutoverWidth = 0;
  g_irisMetal4PendingCutoverHeight = 0;
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nResetIrisMetal4PresentationBindings(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> executionLock(g_irisMetal4ExecutionMutex);
  CGLContextObj context = CGLGetCurrentContext();
  if (context && (!g_irisMetal4CutoverGlBindings.empty() ||
                  g_irisMetal4PendingCutoverSurface)) {
    // Display transitions are rare. Complete outstanding GL reads before
    // recycling their IOSurfaces, but preserve in-flight graph tokens and all
    // persistent graph/input resources.
    glFinish();
  }
  if (g_irisMetal4PendingCutoverSurface) {
    if (!iris_metal4_recycle_graph_surface(
            g_irisMetal4PendingCutoverSurface,
            g_irisMetal4PendingCutoverWidth,
            g_irisMetal4PendingCutoverHeight)) {
      CFRelease(g_irisMetal4PendingCutoverSurface);
    }
    g_irisMetal4PendingCutoverSurface = nullptr;
  }
  g_irisMetal4PendingCutoverWidth = 0;
  g_irisMetal4PendingCutoverHeight = 0;
  for (auto &entry : g_irisMetal4CutoverGlBindings) {
    IrisMetal4CutoverGlBinding &binding = entry.second;
    if (context && binding.completionFence) {
      glDeleteSync(binding.completionFence);
      binding.completionFence = nullptr;
    }
    if (binding.surface && !iris_metal4_recycle_graph_surface(
            binding.surface, binding.width, binding.height)) {
      CFRelease(binding.surface);
    }
    binding.surface = nullptr;
  }
  g_irisMetal4CutoverGlBindings.clear();
  g_metal4DrawPathActive.store(false, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nResetIrisMetal4FinalCutoverSurface(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> executionLock(g_irisMetal4ExecutionMutex);
  CGLContextObj context = CGLGetCurrentContext();
  if (context && (!g_irisMetal4CutoverGlBindings.empty() ||
                  !g_irisMetal4InputHandoffs.empty() ||
                  !g_irisMetal4InputSurfaceHandoffs.empty())) {
    glFinish();
  }
  if (g_irisMetal4PendingCutoverSurface) {
    CFRelease(g_irisMetal4PendingCutoverSurface);
    g_irisMetal4PendingCutoverSurface = nullptr;
  }
  for (auto &entry : g_irisMetal4CutoverGlBindings) {
    if (context && entry.second.completionFence) {
      glDeleteSync(entry.second.completionFence);
    }
    if (entry.second.surface) {
      CFRelease(entry.second.surface);
    }
  }
  g_irisMetal4CutoverGlBindings.clear();
  for (auto &entry : g_irisMetal4InputHandoffs) {
    destroy_iris_metal4_input_handoff(entry.second, context != nullptr);
  }
  g_irisMetal4InputHandoffs.clear();
  g_irisMetal4InputHandoffBytes = 0;
  for (auto &entry : g_irisMetal4InputSurfaceHandoffs) {
    destroy_iris_metal4_input_handoff(entry.second,
                                      context != nullptr);
  }
  g_irisMetal4InputSurfaceHandoffs.clear();
  g_irisMetal4InputSurfaceCaptureSequences.clear();
  g_irisMetal4InputSurfaceBytes = 0;
  for (auto &entry : g_irisMetal4ResidentBuffers) {
    if (entry.second.buffer) {
      [entry.second.buffer release];
    }
  }
  g_irisMetal4ResidentBuffersByToken.clear();
  g_irisMetal4ResidentBuffers.clear();
  g_irisMetal4ResidentBufferBytes = 0;
  g_irisMetal4LastGraphFrameRgba8.clear();
  reset_iris_metal4_graph_resources();
  g_irisMetal4PendingCutoverWidth = 0;
  g_irisMetal4PendingCutoverHeight = 0;
  g_metal4DrawPathActive.store(false, std::memory_order_release);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nTakeIrisMetal4ShadowReplayRgba8(
    JNIEnv *env, jclass) {
  std::vector<uint8_t> pixels;
  pixels.swap(g_irisMetal4LastReplayRgba8);
  if (pixels.size() > (size_t)std::numeric_limits<jsize>::max())
    return nullptr;
  jbyteArray result = env->NewByteArray((jsize)pixels.size());
  if (!result || pixels.empty())
    return result;
  env->SetByteArrayRegion(result, 0, (jsize)pixels.size(),
                          reinterpret_cast<const jbyte *>(pixels.data()));
  return env->ExceptionCheck() ? nullptr : result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nTakeIrisMetal4GraphFrameRgba8(
    JNIEnv *env, jclass) {
  std::vector<uint8_t> pixels;
  pixels.swap(g_irisMetal4LastGraphFrameRgba8);
  if (pixels.size() > (size_t)std::numeric_limits<jsize>::max())
    return nullptr;
  jbyteArray result = env->NewByteArray((jsize)pixels.size());
  if (!result || pixels.empty())
    return result;
  env->SetByteArrayRegion(result, 0, (jsize)pixels.size(),
                          reinterpret_cast<const jbyte *>(pixels.data()));
  return env->ExceptionCheck() ? nullptr : result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nRunIrisMetal4ShadowParitySmoke(
    JNIEnv *env, jclass, jstring pipelineKeyValue, jint width, jint height,
    jint expectedRgba, jint channelTolerance) {
  @autoreleasepool {
    if (!g_device || !g_metal4CommandQueue ||
        !g_metal4RuntimeVerified.load(std::memory_order_acquire))
      return 0;
    if (width <= 0 || height <= 0 || width > 1024 || height > 1024 ||
        channelTolerance < 0 || channelTolerance > 16 || !pipelineKeyValue)
      return -1;
    if (@available(macOS 26.0, *)) {
      const char *pipelineKey =
          env->GetStringUTFChars(pipelineKeyValue, nullptr);
      if (!pipelineKey)
        return -1;
      std::string key(pipelineKey);
      bool validKey = valid_iris_pipeline_key(pipelineKey);
      env->ReleaseStringUTFChars(pipelineKeyValue, pipelineKey);
      if (!validKey)
        return -1;

      IrisMetal4PipelineEntry entry;
      {
        std::lock_guard<std::mutex> lock(g_irisMetal4PipelineCacheMutex);
        auto found = g_irisMetal4Pipelines.find(key);
        if (found == g_irisMetal4Pipelines.end() || !found->second.render)
          return -1;
        entry = found->second;
        [entry.render retain];
        if (entry.depthStencil)
          [entry.depthStencil retain];
      }
      MTLPrimitiveType primitiveType = MTLPrimitiveTypeTriangle;
      if (!iris_metal_primitive_type(entry.topology, primitiveType)) {
        [entry.render release];
        if (entry.depthStencil)
          [entry.depthStencil release];
        return 0;
      }

      g_irisMetal4PipelineDrawAttemptCount.fetch_add(
          1, std::memory_order_relaxed);
      id<MTLTexture> target = nil;
      id<MTLResidencySet> residency = nil;
      id<MTL4CommandAllocator> allocator = nil;
      id<MTL4CommandBuffer> commandBuffer = nil;
      MTL4RenderPassDescriptor *pass = nil;
      MTL4CommitOptions *options = nil;
      jint result = -1;
      @try {
        MTLTextureDescriptor *textureDescriptor =
            [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:
                MTLPixelFormatRGBA8Unorm
                                                        width:(NSUInteger)width
                                                       height:(NSUInteger)height
                                                    mipmapped:NO];
        textureDescriptor.storageMode = MTLStorageModeShared;
        textureDescriptor.usage = MTLTextureUsageRenderTarget |
                                  MTLTextureUsageShaderRead;
        target = [g_device newTextureWithDescriptor:textureDescriptor];

        MTLResidencySetDescriptor *residencyDescriptor =
            [[MTLResidencySetDescriptor alloc] init];
        residencyDescriptor.label = @"MetalRender Iris shadow smoke";
        residencyDescriptor.initialCapacity = 1;
        NSError *residencyError = nil;
        residency = [g_device
            newResidencySetWithDescriptor:residencyDescriptor
                                    error:&residencyError];
        [residencyDescriptor release];
        if (!target || !residency)
          @throw [NSException exceptionWithName:@"MetalRenderShadowSetup"
                                         reason:@"offscreen allocation failed"
                                       userInfo:nil];
        [residency addAllocation:(id<MTLAllocation>)target];
        [residency commit];
        [residency requestResidency];

        allocator = [g_device newCommandAllocator];
        commandBuffer = [g_device newCommandBuffer];
        if (!allocator || !commandBuffer)
          @throw [NSException exceptionWithName:@"MetalRenderShadowSetup"
                                         reason:@"command allocation failed"
                                       userInfo:nil];
        [commandBuffer beginCommandBufferWithAllocator:allocator];
        [commandBuffer useResidencySet:residency];
        pass = [[MTL4RenderPassDescriptor alloc] init];
        MTLRenderPassColorAttachmentDescriptor *color =
            pass.colorAttachments[0];
        color.texture = target;
        color.loadAction = MTLLoadActionClear;
        color.storeAction = MTLStoreActionStore;
        color.clearColor = MTLClearColorMake(1.0, 0.0, 1.0, 1.0);
        pass.renderTargetWidth = (NSUInteger)width;
        pass.renderTargetHeight = (NSUInteger)height;
        id<MTL4RenderCommandEncoder> encoder =
            [commandBuffer renderCommandEncoderWithDescriptor:pass];
        if (!encoder)
          @throw [NSException exceptionWithName:@"MetalRenderShadowSetup"
                                         reason:@"render encoder unavailable"
                                       userInfo:nil];
        [encoder setRenderPipelineState:entry.render];
        [encoder setDepthStencilState:entry.depthStencil];
        [encoder setCullMode:entry.cullMode];
        [encoder setFrontFacingWinding:entry.frontFacingWinding];
        [encoder setTriangleFillMode:entry.triangleFillMode];
        [encoder setDepthClipMode:entry.depthClipMode];
        uint32_t offsetBit = entry.topology == 0 ? 1u
            : (entry.topology >= 1 && entry.topology <= 3 ? 2u : 4u);
        if ((entry.polygonOffsetMask & offsetBit) != 0) {
          [encoder setDepthBias:entry.depthBias
                     slopeScale:entry.slopeScale
                          clamp:entry.depthBiasClamp];
        }
        [encoder setStencilFrontReferenceValue:entry.frontStencilReference
                            backReferenceValue:entry.backStencilReference];
        MTLViewport viewport = {0.0, 0.0, (double)width, (double)height,
                                0.0, 1.0};
        [encoder setViewport:viewport];
        MTLScissorRect scissor = {0, 0, (NSUInteger)width,
                                  (NSUInteger)height};
        [encoder setScissorRect:scissor];
        [encoder drawPrimitives:primitiveType vertexStart:0 vertexCount:3];
        [encoder endEncoding];
        [commandBuffer endCommandBuffer];

        auto state = std::make_shared<Metal4ProbeState>(allocator);
        bool completed = metal4_commit_and_wait(
            (id<MTL4CommandQueue>)g_metal4CommandQueue,
            commandBuffer, state, 5'000);
        if (!completed ||
            !state->completed.load(std::memory_order_acquire) ||
            !state->succeeded.load(std::memory_order_acquire)) {
          result = -1;
        } else {
          size_t byteCount = (size_t)width * (size_t)height * 4u;
          std::vector<uint8_t> pixels(byteCount);
          MTLRegion region = MTLRegionMake2D(0, 0, (NSUInteger)width,
                                             (NSUInteger)height);
          [target getBytes:pixels.data()
               bytesPerRow:(NSUInteger)width * 4u
                fromRegion:region
               mipmapLevel:0];
          uint32_t expected = (uint32_t)expectedRgba;
          uint8_t channels[] = {(uint8_t)(expected >> 24),
                                (uint8_t)(expected >> 16),
                                (uint8_t)(expected >> 8),
                                (uint8_t)expected};
          bool matches = true;
          for (size_t offset = 0; offset < byteCount && matches;
               offset += 4) {
            for (size_t channel = 0; channel < 4; channel++) {
              int delta = (int)pixels[offset + channel] - channels[channel];
              if (std::abs(delta) > channelTolerance) {
                matches = false;
                break;
              }
            }
          }
          result = matches ? 1 : 2;
        }
      } @catch (NSException *exception) {
        dbg("WARN: Iris Metal 4 shadow smoke raised %s: %s\n",
            exception.name.UTF8String ?: "NSException",
            exception.reason.UTF8String ?: "unknown reason");
        result = -1;
      }
      if (options)
        [options release];
      if (pass)
        [pass release];
      if (commandBuffer)
        [commandBuffer release];
      if (allocator)
        [allocator release];
      if (residency) {
        [residency endResidency];
        [residency release];
      }
      if (target)
        [target release];
      [entry.render release];
      if (entry.depthStencil)
        [entry.depthStencil release];
      return result;
    }
    return 0;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nResetIrisMetal4Pipelines(
    JNIEnv *, jclass) {
  @autoreleasepool {
    std::lock_guard<std::mutex> lock(g_irisMetal4PipelineCacheMutex);
    reset_iris_metal4_pipeline_cache_locked();
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsIrisMslCompilerReady(
    JNIEnv *, jclass) {
  return g_irisMslCompilerReady.load(std::memory_order_acquire) ? JNI_TRUE
                                                                : JNI_FALSE;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetDeviceName(
    JNIEnv *env, jclass) {
  ensure_device();
  if (!g_device)
    return env->NewStringUTF("unknown");
  NSString *name = [g_device name];
  return env->NewStringUTF([name UTF8String]);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSupportsIndirect(
    JNIEnv *, jclass) {
  MetalFeatureCaps caps = current_feature_caps();
  return caps.indirectCommandBuffers ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSupportsMeshShaders(
    JNIEnv *, jclass) {
  MetalFeatureCaps caps = current_feature_caps();
  return caps.meshShaders ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nConfigureRuntime(
    JNIEnv *, jclass, jboolean enableMetal4, jint memoryBudgetMB,
    jint targetFrameRate, jboolean tripleBuffering) {
  g_metal4Requested.store(enableMetal4 == JNI_TRUE,
                          std::memory_order_release);
  if (memoryBudgetMB > 0) {
    size_t requestedMB =
        (size_t)std::min((int)(kMegaVBHardMaximum / kMiB),
                         std::max(1, (int)memoryBudgetMB));
    g_requestedMemoryBudgetBytes = requestedMB * kMiB;
  }
  if (targetFrameRate > 0) {
    int clampedFps = std::max(30, std::min(1000, (int)targetFrameRate));
    g_targetFrameTimeMs = 1000.0f / (float)clampedFps;
  }
  bool useTripleBuffering = tripleBuffering == JNI_TRUE;
  bool bufferingChanged =
      g_tripleBufferingEnabled.exchange(useTripleBuffering,
                                         std::memory_order_acq_rel) !=
      useTripleBuffering;
  g_activeSurfaceSlots.store(useTripleBuffering ? 3 : 2,
                             std::memory_order_release);

  ensure_device();
  if (bufferingChanged && g_tbColor[0])
    drain_surface_slots(true);
  recreate_mega_vertex_buffer_if_empty();
  if (enableMetal4 == JNI_TRUE) {
    configure_metal4_scaffold();
  } else {
    std::lock_guard<std::mutex> lock(g_metal4Mutex);
    g_metal4ScaffoldActive.store(false, std::memory_order_release);
    g_metal4RuntimeVerified.store(false, std::memory_order_release);
    g_metal4ProbeInProgress.store(false, std::memory_order_release);
    g_metal4ProbeAttempted.store(false, std::memory_order_release);
    if (g_metal4CommandQueue) {
      [g_metal4CommandQueue release];
      g_metal4CommandQueue = nil;
    }
    if (g_metal4CommandAllocator) {
      [g_metal4CommandAllocator release];
      g_metal4CommandAllocator = nil;
    }
  }
  dbg("Runtime configured: metal4Requested=%d metal4Verified=%d "
      "arena=%zuMB target=%.2fms surfaces=%d\n",
      enableMetal4 == JNI_TRUE ? 1 : 0,
      g_metal4RuntimeVerified.load(std::memory_order_relaxed) ? 1 : 0,
      g_megaVBCapacity / kMiB, g_targetFrameTimeMs,
      g_activeSurfaceSlots.load(std::memory_order_relaxed));
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSupportsMetal4(
    JNIEnv *, jclass) {
  ensure_device();
  configure_metal4_scaffold();
  return g_metal4Supported.load(std::memory_order_acquire) ? JNI_TRUE
                                                           : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsMetal4Active(
    JNIEnv *, jclass) {
  // "Active" means an actual MTL4 command buffer was encoded, committed and
  // completed without feedback errors. Use nIsMetal4DrawPathActive to
  // distinguish the later fully migrated encoder/pipeline path.
  return g_metal4RuntimeVerified.load(std::memory_order_acquire) ? JNI_TRUE
                                                                : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsMetal4DrawPathActive(
    JNIEnv *, jclass) {
  return g_metal4DrawPathActive.load(std::memory_order_acquire) ? JNI_TRUE
                                                                : JNI_FALSE;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetBackendMode(
    JNIEnv *env, jclass) {
  const char *mode = "METAL3";
  if (g_metal4DrawPathActive.load(std::memory_order_acquire)) {
    mode = "METAL4";
  } else if (g_metal4RuntimeVerified.load(std::memory_order_acquire)) {
    mode = "METAL4_RUNTIME_VERIFIED_METAL3_RENDER";
  } else if (g_metal4ProbeInProgress.load(std::memory_order_acquire)) {
    mode = "METAL3_FALLBACK_METAL4_PROBE_PENDING";
  } else if (g_metal4ScaffoldActive.load(std::memory_order_acquire)) {
    mode = "METAL3_FALLBACK_METAL4_PROBE_PENDING";
  } else if (g_metal4Requested.load(std::memory_order_acquire) &&
             g_metal4Supported.load(std::memory_order_acquire)) {
    mode = "METAL3_FALLBACK_METAL4_PROBE_FAILED";
  } else if (g_metal4Requested.load(std::memory_order_acquire) &&
             !g_metal4Supported.load(std::memory_order_acquire)) {
    mode = "METAL3_FALLBACK_NO_METAL4";
  }
  return env->NewStringUTF(mode);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetCurrentThreadQoS(
    JNIEnv *, jclass, jint qosClass) {
  qos_class_t qos = QOS_CLASS_DEFAULT;
  switch ((int)qosClass) {
  case 1:
    qos = QOS_CLASS_USER_INTERACTIVE;
    break;
  case 2:
    qos = QOS_CLASS_USER_INITIATED;
    break;
  case 3:
    qos = QOS_CLASS_UTILITY;
    break;
  case 4:
    qos = QOS_CLASS_BACKGROUND;
    break;
  default:
    qos = QOS_CLASS_DEFAULT;
    break;
  }
  int result = pthread_set_qos_class_self_np(qos, 0);
  if (result != 0)
    dbg("WARN: pthread_set_qos_class_self_np failed: %d\n", result);
}
static std::shared_mutex g_bufferMutex;
static uint64_t store_buffer(id<MTLBuffer> buf) {
  if (!buf)
    return 0;
  std::unique_lock<std::shared_mutex> lock(g_bufferMutex);
  size_t length = (size_t)buf.length;
  if (g_megaVBBudget > 0 &&
      g_megaVBCapacity + g_individualBufferBytes + length >
          g_megaVBBudget) {
    dbg("Buffer allocation rejected by runtime budget: request=%zuKB "
        "arena=%zuMB individual=%zuMB budget=%zuMB\n",
        length / 1024, g_megaVBCapacity / kMiB,
        g_individualBufferBytes / kMiB, g_megaVBBudget / kMiB);
    [buf release];
    return 0;
  }
  uint64_t h = g_nextHandle++;
  g_buffers[h] = buf;
  g_bufferSizes[h] = length;
  g_individualBufferBytes += length;
  return h;
}
static id<MTLBuffer> get_buffer(uint64_t h) {
  std::shared_lock<std::shared_mutex> lock(g_bufferMutex);
  auto it = g_buffers.find(h);
  if (it == g_buffers.end())
    return nil;
  return it->second;
}
static ResolvedBuf resolve_buffer(uint64_t h) {
  if (isMegaHandle(h)) {
    std::shared_lock<std::shared_mutex> lock(g_megaMutex);
    auto it = g_megaAllocs.find(h);
    if (it != g_megaAllocs.end() && g_megaVB)
      return {g_megaVB, it->second.offset};
    return {nil, 0};
  }
  std::shared_lock<std::shared_mutex> lock(g_bufferMutex);
  auto it = g_buffers.find(h);
  if (it != g_buffers.end())
    return {it->second, 0};
  return {nil, 0};
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_initNative(
    JNIEnv *, jclass, jlong windowHandle, jboolean someFlag) {
  (void)windowHandle;
  (void)someFlag;
  ensure_device();
  return (g_device != nil) ? (jlong)0xBEEF : (jlong)0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_uploadStaticMesh(
    JNIEnv *env, jclass, jlong handle, jobject vertexData, jint vertexCount,
    jint stride) {
  (void)handle;
  (void)vertexCount;
  (void)stride;
  if (!vertexData)
    return;
  void *ptr = env->GetDirectBufferAddress(vertexData);
  jlong cap = env->GetDirectBufferCapacity(vertexData);
  (void)ptr;
  (void)cap;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_resize(
    JNIEnv *, jclass, jlong handle, jint width, jint height) {
  (void)handle;
  (void)width;
  (void)height;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_setCamera(
    JNIEnv *env, jclass, jlong handle, jfloatArray viewProj4x4) {
  (void)handle;
  if (!viewProj4x4)
    return;
  jfloat tmp[16];
  if (env->GetArrayLength(viewProj4x4) >= 16) {
    env->GetFloatArrayRegion(viewProj4x4, 0, 16, tmp);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_render(
    JNIEnv *, jclass, jlong handle, jfloat timeSeconds) {
  (void)handle;
  (void)timeSeconds;
  ensure_device();
  ensure_offscreen();
  if (!g_device || !g_queue || !g_color || !g_depth)
    return;
  @autoreleasepool {
    id<MTLCommandBuffer> cb = [g_queue commandBuffer];
    MTLRenderPassDescriptor *rp =
        [MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture = g_color;
    rp.colorAttachments[0].loadAction = MTLLoadActionClear;
    rp.colorAttachments[0].storeAction = MTLStoreActionStore;
    rp.colorAttachments[0].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 0.0);
    rp.depthAttachment.texture = g_depth;
    rp.depthAttachment.loadAction = MTLLoadActionClear;
    rp.depthAttachment.storeAction = MTLStoreActionStore;
    rp.depthAttachment.clearDepth = 0.0;
    id<MTLRenderCommandEncoder> enc =
        [cb renderCommandEncoderWithDescriptor:rp];
    [enc endEncoding];
    [cb commit];
  }
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_createVertexBuffer(
    JNIEnv *env, jclass, jlong handle, jobject data, jint size) {
  (void)handle;
  ensure_device();
  if (!g_device || !data || size <= 0)
    return 0;
  void *ptr = env->GetDirectBufferAddress(data);
  jlong cap = env->GetDirectBufferCapacity(data);
  if (!ptr || cap < size)
    return 0;
  id<MTLBuffer> buf = [g_device newBufferWithLength:(size_t)size
                                            options:MTLStorageModeShared];
  memcpy([buf contents], ptr, (size_t)size);
  uint64_t h = store_buffer(buf);
  return (jlong)h;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_createIndexBuffer(
    JNIEnv *env, jclass, jlong handle, jobject data, jint size) {
  (void)handle;
  ensure_device();
  if (!g_device || !data || size <= 0)
    return 0;
  void *ptr = env->GetDirectBufferAddress(data);
  jlong cap = env->GetDirectBufferCapacity(data);
  if (!ptr || cap < size)
    return 0;
  id<MTLBuffer> buf = [g_device newBufferWithLength:(size_t)size
                                            options:MTLStorageModeShared];
  memcpy([buf contents], ptr, (size_t)size);
  uint64_t h = store_buffer(buf);
  return (jlong)h;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MetalBackend_destroyBuffer(
    JNIEnv *, jclass, jlong handle, jlong bufferHandle) {
  (void)handle;
  uint64_t h = (uint64_t)bufferHandle;
  std::lock_guard<std::mutex> lock(g_deferredMutex);
  uint64_t retireAfter =
      g_submittedFrameSerial.load(std::memory_order_acquire) +
      kDeferredSubmissionDelay;
  g_deferredDeletions.push_back({h, retireAfter, isMegaHandle(h)});
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nCreateBufferWithHint(
    JNIEnv *, jclass, jlong deviceHandle, jint sizeBytes, jint storageMode,
    jlong oldHandle) {
  (void)deviceHandle;
  (void)storageMode;
  (void)oldHandle;
  ensure_device();
  if (!g_device || sizeBytes <= 0)
    return 0;
  size_t aligned = (size_t)((sizeBytes + 255) & ~255);
  // A chunk rebuild is prepared before it replaces the published mesh. Never
  // upload into oldHandle in place: the previous mesh can still be selected by
  // the render thread or referenced by an in-flight command buffer. Allocate a
  // distinct range and let the normal deferred-deletion path retire the old
  // handle only after publication.
  if (g_megaVB && aligned <= 16 * 1024 * 1024) {
    uint64_t megaH = megaAlloc(aligned);
    if (megaH != 0) {
      return (jlong)megaH;
    }
  }
  id<MTLBuffer> buf = [g_device newBufferWithLength:(size_t)sizeBytes
                                            options:MTLStorageModeShared];
  return (jlong)store_buffer(buf);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nCreateBuffer(
    JNIEnv *, jclass, jlong deviceHandle, jint sizeBytes, jint storageMode) {
  (void)deviceHandle;
  (void)storageMode;
  ensure_device();
  if (!g_device || sizeBytes <= 0)
    return 0;
  if (g_megaVB && sizeBytes <= 16 * 1024 * 1024) {
    uint64_t megaH = megaAlloc((size_t)sizeBytes);
    if (megaH != 0) {
      return (jlong)megaH;
    }
  }
  id<MTLBuffer> buf = [g_device newBufferWithLength:(size_t)sizeBytes
                                            options:MTLStorageModeShared];
  return (jlong)store_buffer(buf);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadBufferData(
    JNIEnv *env, jclass, jlong bufferHandle, jbyteArray data, jint offset,
    jint length) {
  if (!data || offset < 0 || length <= 0 ||
      (jlong)length > (jlong)env->GetArrayLength(data))
    return;
  size_t destinationOffset = (size_t)offset;
  size_t copyLength = (size_t)length;
  uint64_t h = (uint64_t)bufferHandle;
  if (isMegaHandle(h)) {
    MegaSubAlloc alloc;
    if (!megaGetAlloc(h, alloc) || destinationOffset > alloc.size ||
        copyLength > alloc.size - destinationOffset)
      return;
    void *dst = megaGetPointer(h);
    if (!dst)
      return;
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes) {
      memcpy((uint8_t *)dst + destinationOffset, bytes, copyLength);
      [g_megaVB
          didModifyRange:NSMakeRange(
                             (NSUInteger)(alloc.offset + destinationOffset),
                             (NSUInteger)copyLength)];
      env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    }
    return;
  }
  id<MTLBuffer> buf = get_buffer(h);
  if (!buf || destinationOffset > (size_t)buf.length ||
      copyLength > (size_t)buf.length - destinationOffset)
    return;
  jbyte *bytes = env->GetByteArrayElements(data, nullptr);
  if (bytes) {
    memcpy((uint8_t *)[buf contents] + destinationOffset, bytes, copyLength);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadBufferDataDirect(
    JNIEnv *env, jclass, jlong bufferHandle, jobject directBuffer, jint offset,
    jint length) {
  if (!directBuffer || offset < 0 || length <= 0)
    return;
  jlong capacity = env->GetDirectBufferCapacity(directBuffer);
  if (capacity < 0 || (jlong)offset > capacity ||
      (jlong)length > capacity - (jlong)offset)
    return;
  void *ptr = env->GetDirectBufferAddress(directBuffer);
  if (!ptr)
    return;
  size_t sourceOffset = (size_t)offset;
  size_t copyLength = (size_t)length;
  uint64_t h = (uint64_t)bufferHandle;
  if (isMegaHandle(h)) {
    MegaSubAlloc alloc;
    if (!megaGetAlloc(h, alloc) || copyLength > alloc.size)
      return;
    void *dst = megaGetPointer(h);
    if (!dst)
      return;
    memcpy((uint8_t *)dst, (uint8_t *)ptr + sourceOffset, copyLength);
    [g_megaVB didModifyRange:NSMakeRange((NSUInteger)alloc.offset,
                                         (NSUInteger)copyLength)];
    return;
  }
  id<MTLBuffer> buf = get_buffer(h);
  if (!buf || copyLength > (size_t)buf.length)
    return;
  memcpy((uint8_t *)[buf contents], (uint8_t *)ptr + sourceOffset, copyLength);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDestroyBuffer(
    JNIEnv *, jclass, jlong bufferHandle) {
  uint64_t h = (uint64_t)bufferHandle;
  std::lock_guard<std::mutex> lock(g_deferredMutex);
  uint64_t retireAfter =
      g_submittedFrameSerial.load(std::memory_order_acquire) +
      kDeferredSubmissionDelay;
  g_deferredDeletions.push_back({h, retireAfter, isMegaHandle(h)});
}
static id<MTLRenderPipelineState> g_currentPipeline = nil;
static float g_chunkOffsetX = 0, g_chunkOffsetY = 0, g_chunkOffsetZ = 0;
static float g_projMatrix[16] = {};
static float g_mvMatrix[16] = {};
static float g_mvpMatrix[16] = {};
static double g_camX = 0, g_camY = 0, g_camZ = 0;
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetPipelineState(
    JNIEnv *, jclass, jlong frameContext, jlong pipelineHandle) {
  (void)frameContext;
  if (g_currentEncoder && pipelineHandle != 0) {
    id<MTLRenderPipelineState> pipeline =
        (__bridge id<MTLRenderPipelineState>)(void *)(uintptr_t)pipelineHandle;
    [g_currentEncoder setRenderPipelineState:pipeline];
    g_currentPipeline = pipeline;
    bool isTranslucentPipeline = (pipeline == g_pipelineEntityTranslucent ||
                                  pipeline == g_pipelineParticle);
    id<MTLDepthStencilState> ds =
        isTranslucentPipeline ? g_depthStateLessEqual : g_depthState;
    if (ds)
      [g_currentEncoder setDepthStencilState:ds];
  } else if (g_currentEncoder && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetChunkOffset(
    JNIEnv *, jclass, jlong frameContext, jfloat x, jfloat y, jfloat z) {
  (void)frameContext;
  g_chunkOffsetX = x;
  g_chunkOffsetY = y;
  g_chunkOffsetZ = z;
  if (g_currentEncoder) {
    float offset[4] = {x, y, z, 0.0f};
    [g_currentEncoder setVertexBytes:offset length:sizeof(offset) atIndex:4];
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawIndexedBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer, jlong indexBuffer,
    jint indexCount, jint baseIndex) {
  (void)frameContext;
  if (!g_currentEncoder || indexCount <= 0) {
    g_drawSkipCount++;
    return;
  }
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
  if (!vbRes.buf || !ibRes.buf)
    return;
  id<MTLBuffer> vb = vbRes.buf;
  id<MTLBuffer> ib = ibRes.buf;
  g_totalDraws++;
  if (g_totalDraws <= 3) {
    const short *vtx = (const short *)((uint8_t *)[vb contents] + vbRes.offset);
    const uint32_t *idx =
        (const uint32_t *)((uint8_t *)[ib contents] + ibRes.offset);
    dbg("Draw #%d: vb=%p(%luB+%zu) ib=%p(%luB+%zu) idxCount=%d baseIdx=%d\n",
        g_totalDraws, vb, (unsigned long)[vb length], vbRes.offset, ib,
        (unsigned long)[ib length], ibRes.offset, indexCount, baseIndex);
    dbg("  First vertex (shorts): %d %d %d %d %d | bytes: %d %d %d %d | %d "
        "%d\n",
        vtx[0], vtx[1], vtx[2], vtx[3], vtx[4], ((const uint8_t *)vtx)[10],
        ((const uint8_t *)vtx)[11], ((const uint8_t *)vtx)[12],
        ((const uint8_t *)vtx)[13], ((const uint8_t *)vtx)[14],
        ((const uint8_t *)vtx)[15]);
    dbg("  First indices: %u %u %u %u %u %u\n", idx[0], idx[1], idx[2], idx[3],
        idx[4], idx[5]);
    dbg("  ChunkOffset: %.2f %.2f %.2f\n", g_chunkOffsetX, g_chunkOffsetY,
        g_chunkOffsetZ);
  }
  if (!g_currentPipeline && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
  if (!g_currentPipeline)
    return;
  [g_currentEncoder setVertexBuffer:vb
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder
      drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                 indexCount:(NSUInteger)indexCount
                  indexType:MTLIndexTypeUInt32
                indexBuffer:ib
          indexBufferOffset:(NSUInteger)(ibRes.offset +
                                         (size_t)baseIndex * sizeof(uint32_t))];
  g_drawCallCount++;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawIndexedBatch(
    JNIEnv *env, jclass, jlong frameContext, jlong indexBuffer,
    jfloatArray drawData, jint drawCount) {
  @autoreleasepool {
    (void)frameContext;
    if (!g_currentEncoder || drawCount <= 0 || !drawData)
      return;
    uint64_t t0 = mach_absolute_time();
    ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
    if (!ibRes.buf)
      return;
    id<MTLBuffer> ib = ibRes.buf;
    NSUInteger ibOffset = (NSUInteger)ibRes.offset;
    if (!g_currentPipeline && g_pipelineInhouse) {
      id<MTLRenderPipelineState> waterPipeline =
          g_pipelineWater ? g_pipelineWater : g_pipelineInhouse;
      [g_currentEncoder setRenderPipelineState:waterPipeline];
      g_currentPipeline = waterPipeline;
      if (g_depthState)
        [g_currentEncoder setDepthStencilState:g_depthState];
    }
    if (!g_currentPipeline)
      return;
    int len = env->GetArrayLength(drawData);
    int stride = 6;
    if (len < drawCount * stride)
      return;
    jfloat *data = env->GetFloatArrayElements(drawData, nullptr);
    if (!data)
      return;
    uint64_t t1 = mach_absolute_time();
    static id<MTLBuffer> g_batchOffsetBuf = nil;
    static size_t g_batchOffsetCap = 0;
    size_t offsetBufSize = (size_t)drawCount * 16;
    if (!g_batchOffsetBuf || g_batchOffsetCap < offsetBufSize) {
      g_batchOffsetCap = offsetBufSize * 2;
      if (g_batchOffsetBuf)
        [g_batchOffsetBuf release];
      g_batchOffsetBuf = [g_device newBufferWithLength:g_batchOffsetCap
                                               options:MTLStorageModeShared];
    }
    float *offBuf = (float *)[g_batchOffsetBuf contents];
    struct DrawCmd {
      uint64_t bufHandle;
      size_t megaOffset;
      id<MTLBuffer> resolvedBuf;
      int idxCount;
      int opaqueIdxCount;
      float distSq;
      float ox, oy, oz;
      bool isMega;
    };
    DrawCmd stackCmds[256];
    DrawCmd *cmds = (drawCount <= 256) ? stackCmds : new DrawCmd[drawCount];
    int validCount = 0;
    int megaCount = 0;
    static const int VERTEX_STRIDE = 16;
    {
      std::shared_lock<std::shared_mutex> megaLock(g_megaMutex);
      std::shared_lock<std::shared_mutex> bufLock(g_bufferMutex);
      for (int i = 0; i < drawCount; i++) {
        int off = i * stride;
        uint32_t hi = *(uint32_t *)&data[off + 0];
        uint32_t lo = *(uint32_t *)&data[off + 1];
        uint64_t bufHandle = ((uint64_t)hi << 32) | (uint64_t)lo;
        int idxCount = *(int *)&data[off + 5];
        if (idxCount <= 0)
          continue;
        float ox = data[off + 2];
        float oy = data[off + 3];
        float oz = data[off + 4];
        float cx = ox + 8.0f;
        float cy = oy + 8.0f;
        float cz = oz + 8.0f;
        float distSq = cx * cx + cy * cy + cz * cz;
        bool mega = isMegaHandle(bufHandle);
        if (mega) {
          auto it = g_megaAllocs.find(bufHandle);
          if (it == g_megaAllocs.end())
            continue;
          cmds[validCount] = {bufHandle, it->second.offset,
                              nil,       idxCount,
                              idxCount,  distSq,
                              ox,        oy,
                              oz,        true};
          megaCount++;
        } else {
          id<MTLBuffer> rb = nil;
          auto bit = g_buffers.find(bufHandle);
          if (bit != g_buffers.end())
            rb = bit->second;
          cmds[validCount] = {bufHandle, 0,  rb, idxCount, idxCount,
                              distSq,    ox, oy, oz,       false};
        }
        validCount++;
      }
    }
    env->ReleaseFloatArrayElements(drawData, data, JNI_ABORT);

    if (validCount > 1) {
      std::sort(cmds, cmds + validCount,
                [](const DrawCmd &a, const DrawCmd &b) {
                  return a.distSq < b.distSq;
                });
    }
    for (int i = 0; i < validCount; i++) {
      int oidx = i * 4;
      offBuf[oidx + 0] = cmds[i].ox;
      offBuf[oidx + 1] = cmds[i].oy;
      offBuf[oidx + 2] = cmds[i].oz;
      float cx2 = cmds[i].ox + 8.0f;
      float cy2 = cmds[i].oy + 8.0f;
      float cz2 = cmds[i].oz + 8.0f;
      uint32_t faceMask = 0x3F;
      const float margin = 9.0f;
      if (cy2 > margin)
        faceMask &= ~(1u << 1);
      if (cy2 < -margin)
        faceMask &= ~(1u << 0);
      if (cz2 < -margin)
        faceMask &= ~(1u << 2);
      if (cz2 > margin)
        faceMask &= ~(1u << 3);
      if (cx2 < -margin)
        faceMask &= ~(1u << 4);
      if (cx2 > margin)
        faceMask &= ~(1u << 5);
      float maskAsFloat;
      memcpy(&maskAsFloat, &faceMask, sizeof(float));
      offBuf[oidx + 3] = maskAsFloat;
    }
    uint64_t t2 = mach_absolute_time();
    static uint64_t t_acc_resolve = 0, t_acc_jni = 0, t_acc_classify = 0;
    static uint64_t t_acc_icb_encode = 0, t_acc_icb_exec = 0, t_acc_total = 0;
    static int t_acc_frames = 0;
    static int t_acc_draws = 0;
    static uint64_t t_last_log_frame = 0;
    if (g_frameCount > 0 && g_frameCount != t_last_log_frame &&
        (g_frameCount % 120 == 0)) {
      ensureTimebase();
      auto toUs = [&](uint64_t t) -> double {
        return (double)(t * g_cachedTimebase.numer / g_cachedTimebase.denom) /
               1000.0;
      };
      dbg("TIMING [%d frames, %d draws]: total=%.0fus resolve=%.0fus "
          "jni=%.0fus "
          "classify=%.0fus icb_encode=%.0fus icb_exec=%.0fus\n",
          t_acc_frames, t_acc_draws, toUs(t_acc_total), toUs(t_acc_resolve),
          toUs(t_acc_jni), toUs(t_acc_classify), toUs(t_acc_icb_encode),
          toUs(t_acc_icb_exec));
      dbg("TIMING per-frame avg: total=%.1fus resolve=%.1fus jni=%.1fus "
          "classify=%.1fus icb_encode=%.1fus icb_exec=%.1fus\n",
          toUs(t_acc_total) / MAX(t_acc_frames, 1),
          toUs(t_acc_resolve) / MAX(t_acc_frames, 1),
          toUs(t_acc_jni) / MAX(t_acc_frames, 1),
          toUs(t_acc_classify) / MAX(t_acc_frames, 1),
          toUs(t_acc_icb_encode) / MAX(t_acc_frames, 1),
          toUs(t_acc_icb_exec) / MAX(t_acc_frames, 1));
      t_acc_resolve = t_acc_jni = t_acc_classify = 0;
      t_acc_icb_encode = t_acc_icb_exec = t_acc_total = 0;
      t_acc_frames = 0;
      t_acc_draws = 0;
      t_last_log_frame = g_frameCount;
    }
    if (validCount == 0) {
      if (cmds != stackCmds)
        delete[] cmds;
      return;
    }

    static int g_drawBudget = 65536;
    static const int MIN_BUDGET = 16384;
    static const int MAX_BUDGET = 65536;
    float gpuMs = g_lastGpuMs.load(std::memory_order_relaxed);
    float gpuHighWatermark = g_targetFrameTimeMs * 0.90f;
    float gpuLowWatermark = g_targetFrameTimeMs * 0.75f;
    if (gpuMs > gpuHighWatermark && g_drawBudget > MIN_BUDGET) {
      g_drawBudget = MAX(MIN_BUDGET, (int)(g_drawBudget * 0.92f));
    } else if (gpuMs < gpuLowWatermark && g_drawBudget < MAX_BUDGET) {
      g_drawBudget = MIN(MAX_BUDGET, (int)(g_drawBudget * 1.12f) + 2);
    }
    int preCapCount = validCount;
    if (validCount > g_drawBudget) {
      megaCount = 0;
      for (int i = 0; i < g_drawBudget; i++) {
        if (cmds[i].isMega)
          megaCount++;
      }
      validCount = g_drawBudget;
    }
    if (g_frameCount % 600 == 0) {
      dbg("DRAW_BUDGET: budget=%d, preCap=%d, drawn=%d, gpuMs=%.1f\n",
          g_drawBudget, preCapCount, validCount, gpuMs);
    }
    if (g_megaVB) {
      [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
    }
    [g_currentEncoder setVertexBuffer:g_batchOffsetBuf offset:0 atIndex:4];

    [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                                length:sizeof(g_entityOverlayParams)
                               atIndex:5];
    int icbCandidateCount = 0;
    for (int i = 0; i < validCount; i++) {
      const DrawCmd &cmd = cmds[i];
      if (cmd.opaqueIdxCount <= 0)
        continue;
      if (cmd.isMega) {
        if (!g_megaVB)
          continue;
      } else if (!cmd.resolvedBuf) {
        continue;
      }
      icbCandidateCount++;
    }
    bool canICB =
        (g_gpuDrivenEnabled && g_icbCapable && icbCandidateCount > 0 &&
         g_pipelineInhouseICB && g_fragArgBuf[g_renderSlot] &&
         g_fragArgEncoder && g_blockAtlas && g_lightmap);
    if (canICB) {
      [g_currentEncoder setRenderPipelineState:g_pipelineInhouseICB];
      g_currentPipeline = g_pipelineInhouseICB;
      [g_fragArgEncoder setArgumentBuffer:g_fragArgBuf[g_renderSlot] offset:0];
      [g_fragArgEncoder setTexture:g_blockAtlas atIndex:0];
      [g_fragArgEncoder setTexture:g_lightmap atIndex:1];
      [g_currentEncoder setFragmentBuffer:g_fragArgBuf[g_renderSlot]
                                   offset:0
                                  atIndex:0];
      [g_currentEncoder useResource:g_blockAtlas
                              usage:MTLResourceUsageRead
                             stages:MTLRenderStageFragment];
      [g_currentEncoder useResource:g_lightmap
                              usage:MTLResourceUsageRead
                             stages:MTLRenderStageFragment];
      if (!g_icb[g_renderSlot] ||
          g_icbMaxCommands[g_renderSlot] < (NSUInteger)icbCandidateCount) {
        NSUInteger newSize =
            MAX(ICB_INITIAL_SIZE, (NSUInteger)icbCandidateCount * 2);
        MTLIndirectCommandBufferDescriptor *desc =
            [MTLIndirectCommandBufferDescriptor new];
        desc.commandTypes = MTLIndirectCommandTypeDrawIndexed;
        desc.inheritPipelineState = YES;
        desc.inheritBuffers = YES;
        desc.maxVertexBufferBindCount = 1;

        if (g_icb[g_renderSlot])
          [g_icb[g_renderSlot] release];
        g_icb[g_renderSlot] = [g_device
            newIndirectCommandBufferWithDescriptor:desc
                                   maxCommandCount:newSize
                                           options:MTLStorageModeShared];
        [desc release];
        g_icbMaxCommands[g_renderSlot] = newSize;
        dbg("ICB created: slot=%d maxCommands=%lu\n", g_renderSlot,
            (unsigned long)newSize);
      }
      [g_icb[g_renderSlot]
          resetWithRange:NSMakeRange(0, (NSUInteger)icbCandidateCount)];
      int icbIdx = 0;
      for (int i = 0; i < validCount; i++) {
        const DrawCmd &cmd = cmds[i];
        if (cmd.opaqueIdxCount <= 0) {
          continue;
        }
        if (cmd.isMega) {
          if (!g_megaVB)
            continue;
        } else {
          if (!cmd.resolvedBuf)
            continue;
        }
        id<MTLIndirectRenderCommand> icmd = [g_icb[g_renderSlot]
            indirectRenderCommandAtIndex:(NSUInteger)icbIdx];
        if (!cmd.isMega) {
          [icmd setVertexBuffer:cmd.resolvedBuf offset:0 atIndex:0];
        }
        [icmd drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                         indexCount:(NSUInteger)cmd.opaqueIdxCount
                          indexType:MTLIndexTypeUInt32
                        indexBuffer:ib
                  indexBufferOffset:ibOffset
                      instanceCount:1
                         baseVertex:(NSInteger)(cmd.isMega ? (cmd.megaOffset /
                                                              VERTEX_STRIDE)
                                                           : 0)
                       baseInstance:(NSUInteger)i];
        icbIdx++;
      }
      uint64_t t3 = mach_absolute_time();
      [g_currentEncoder
          executeCommandsInBuffer:g_icb[g_renderSlot]
                        withRange:NSMakeRange(0, (NSUInteger)icbIdx)];
      g_drawCallCount++;
      uint64_t t4 = mach_absolute_time();
      t_acc_resolve += (t1 - t0);
      t_acc_jni += (t1 - t0);
      t_acc_classify += (t2 - t1);
      t_acc_icb_encode += (t3 - t2);
      t_acc_icb_exec += (t4 - t3);
      t_acc_total += (t4 - t0);
      t_acc_frames++;
      t_acc_draws += validCount;
      [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
      g_currentPipeline = g_pipelineInhouse;
      if (g_blockAtlas) {
        [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
      }
      if (g_megaVB) {
        [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
      }
    } else {
      for (int i = 0; i < validCount; i++) {
        const DrawCmd &cmd = cmds[i];
        if (cmd.opaqueIdxCount <= 0) {
          g_drawCallCount++;
          continue;
        }
        if (cmd.isMega) {
          [g_currentEncoder
              drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                         indexCount:(NSUInteger)cmd.opaqueIdxCount
                          indexType:MTLIndexTypeUInt32
                        indexBuffer:ib
                  indexBufferOffset:ibOffset
                      instanceCount:1
                         baseVertex:(NSInteger)(cmd.megaOffset / VERTEX_STRIDE)
                       baseInstance:(NSUInteger)i];
        } else {
          if (cmd.resolvedBuf) {
            [g_currentEncoder setVertexBuffer:cmd.resolvedBuf
                                       offset:0
                                      atIndex:0];
            [g_currentEncoder
                drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                           indexCount:(NSUInteger)cmd.opaqueIdxCount
                            indexType:MTLIndexTypeUInt32
                          indexBuffer:ib
                    indexBufferOffset:ibOffset
                        instanceCount:1
                           baseVertex:0
                         baseInstance:(NSUInteger)i];
            if (g_megaVB) {
              [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
            }
          }
        }
        g_drawCallCount++;
      }
    }
    if (cmds != stackCmds)
      delete[] cmds;
    if (g_frameCount < 5 || (g_frameCount % 600 == 0)) {
      dbg("DrawBatch: total=%d valid=%d mega=%d nonMega=%d icb=%s "
          "megaVBUsed=%zuMB\n",
          drawCount, validCount, megaCount, validCount - megaCount,
          canICB ? "YES" : "NO", g_megaVBHead / (1024 * 1024));
    }
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nRegisterChunkMeshBatch(
    JNIEnv *env, jclass, jint count, jlongArray batchData) {
  if (count <= 0 || !batchData)
    return;
  jsize longLen = env->GetArrayLength(batchData);
  jsize maxCount = (jsize)(longLen / 8);
  if (count > maxCount)
    count = (jint)maxCount;
  jlong *data = env->GetLongArrayElements(batchData, nullptr);
  if (!data)
    return;
  std::unique_lock<std::shared_mutex> lock(g_meshRegMutex);
  for (int i = 0; i < count; i++) {
    int idx = i * 8;
    int32_t cx = (int32_t)(data[idx] & 0xFFFFFFFFLL);
    int32_t cy = (int32_t)(data[idx] >> 32);
    int32_t cz = (int32_t)(data[idx + 1] & 0xFFFFFFFFLL);
    int32_t quadCountI = (int32_t)(data[idx + 1] >> 32);
    uint64_t bufH = (uint64_t)data[idx + 2];
    uint64_t visMask = (uint64_t)data[idx + 3];
    int32_t opaqueQ = (int32_t)(data[idx + 4] & 0xFFFFFFFFLL);
    int32_t f0 = (int32_t)(data[idx + 4] >> 32);
    int32_t f1 = (int32_t)(data[idx + 5] & 0xFFFFFFFFLL);
    int32_t f2 = (int32_t)(data[idx + 5] >> 32);
    int32_t f3 = (int32_t)(data[idx + 6] & 0xFFFFFFFFLL);
    int32_t f4 = (int32_t)(data[idx + 6] >> 32);
    int32_t f5 = (int32_t)(data[idx + 7] & 0xFFFFFFFFLL);
    int32_t f6 = (int32_t)(data[idx + 7] >> 32);
    int64_t key = packMeshKey(cx, cy, cz);
    int32_t faceCounts[14] = {f0, f1, f2, f3, f4, f5, f6, 0, 0, 0, 0, 0, 0, 0};
    int opaqueFaceTotal = f0 + f1 + f2 + f3 + f4 + f5 + f6;
    if (opaqueFaceTotal != opaqueQ) {
      memset(faceCounts, 0, sizeof(faceCounts));
      faceCounts[6] = opaqueQ;
    }
    auto it = g_meshKeyToIdx.find(key);
    if (it != g_meshKeyToIdx.end()) {
      NativeMesh &m = g_nativeMeshes[it->second];
      m.bufferHandle = bufH;
      m.quadCount = quadCountI;
      m.opaqueQuadCount = opaqueQ;
      m.visibilityMask = visMask;
      memcpy(m.facingQuadCounts, faceCounts, sizeof(faceCounts));
      m.active = true;
      continue;
    }
    size_t mIdx;
    if (!g_meshFreeSlots.empty()) {
      mIdx = g_meshFreeSlots.back();
      g_meshFreeSlots.pop_back();
    } else {
      mIdx = g_nativeMeshes.size();
      g_nativeMeshes.push_back({});
    }
    NativeMesh mesh = {};
    mesh.chunkX = cx;
    mesh.chunkY = cy;
    mesh.chunkZ = cz;
    mesh.bufferHandle = bufH;
    mesh.quadCount = quadCountI;
    mesh.opaqueQuadCount = opaqueQ;
    mesh.visibilityMask = visMask;
    memcpy(mesh.facingQuadCounts, faceCounts, sizeof(faceCounts));
    mesh.active = true;
    g_nativeMeshes[mIdx] = mesh;
    g_meshKeyToIdx[key] = mIdx;
    g_activeMeshIndices.push_back((int)mIdx);
    g_activeMeshCount++;
  }
  env->ReleaseLongArrayElements(batchData, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nRegisterChunkMesh(
    JNIEnv *env, jclass, jint cx, jint cy, jint cz, jlong bufferHandle,
    jint quadCount, jint opaqueQuadCount, jlong visibilityMask,
    jintArray facingQuadCounts) {
  int64_t key = packMeshKey(cx, cy, cz);
  int32_t faceCounts[14] = {};
  if (facingQuadCounts) {
    jsize len = env->GetArrayLength(facingQuadCounts);
    jsize copyLen = len < 14 ? len : 14;
    env->GetIntArrayRegion(facingQuadCounts, 0, copyLen, faceCounts);
  }
  int opaqueFaceTotal = 0;
  for (int i = 0; i < 7; i++)
    opaqueFaceTotal += faceCounts[i];
  if (opaqueFaceTotal != opaqueQuadCount) {
    memset(faceCounts, 0, sizeof(faceCounts));
    faceCounts[6] = opaqueQuadCount;
  }
  std::unique_lock<std::shared_mutex> lock(g_meshRegMutex);
  auto it = g_meshKeyToIdx.find(key);
  if (it != g_meshKeyToIdx.end()) {
    NativeMesh &m = g_nativeMeshes[it->second];
    m.bufferHandle = (uint64_t)bufferHandle;
    m.quadCount = quadCount;
    m.opaqueQuadCount = opaqueQuadCount;
    m.visibilityMask = (uint64_t)visibilityMask;
    memcpy(m.facingQuadCounts, faceCounts, sizeof(faceCounts));
    m.active = true;

    return;
  }
  size_t idx;
  if (!g_meshFreeSlots.empty()) {
    idx = g_meshFreeSlots.back();
    g_meshFreeSlots.pop_back();
  } else {
    idx = g_nativeMeshes.size();
    g_nativeMeshes.push_back({});
  }
  NativeMesh mesh = {};
  mesh.chunkX = (int32_t)cx;
  mesh.chunkY = (int32_t)cy;
  mesh.chunkZ = (int32_t)cz;
  mesh.bufferHandle = (uint64_t)bufferHandle;
  mesh.quadCount = (int32_t)quadCount;
  mesh.opaqueQuadCount = (int32_t)opaqueQuadCount;
  mesh.visibilityMask = (uint64_t)visibilityMask;
  memcpy(mesh.facingQuadCounts, faceCounts, sizeof(faceCounts));
  mesh.active = true;
  g_nativeMeshes[idx] = mesh;
  g_meshKeyToIdx[key] = idx;

  g_activeMeshIndices.push_back((int)idx);
  g_activeMeshCount++;
  if (g_activeMeshCount <= 5 || g_activeMeshCount % 2000 == 0) {
    dbg("MeshReg: registered (%d,%d,%d) handle=%llu total=%d\n", cx, cy, cz,
        (unsigned long long)bufferHandle, g_activeMeshCount);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUnregisterChunkMesh(
    JNIEnv *, jclass, jint cx, jint cy, jint cz) {
  int64_t key = packMeshKey(cx, cy, cz);
  std::unique_lock<std::shared_mutex> lock(g_meshRegMutex);
  auto it = g_meshKeyToIdx.find(key);
  if (it == g_meshKeyToIdx.end())
    return;
  size_t idx = it->second;
  g_nativeMeshes[idx].active = false;
  g_meshFreeSlots.push_back(idx);
  g_meshKeyToIdx.erase(it);

  int intIdx = (int)idx;
  for (int k = 0; k < (int)g_activeMeshIndices.size(); k++) {
    if (g_activeMeshIndices[k] == intIdx) {
      g_activeMeshIndices[k] = g_activeMeshIndices.back();
      g_activeMeshIndices.pop_back();
      break;
    }
  }
  g_activeMeshCount--;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawAllVisibleChunks(
    JNIEnv *, jclass, jlong frameContext, jlong indexBuffer) {
  @autoreleasepool {
    (void)frameContext;
    uint64_t _prof_t0 = mach_absolute_time();
    if (!g_currentEncoder)
      return 0;
    ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
    if (!ibRes.buf)
      return 0;
    id<MTLBuffer> ib = ibRes.buf;
    NSUInteger ibOffset = (NSUInteger)ibRes.offset;
    g_deferredWaterCmdCount = 0;
    g_deferredWaterIB = nil;
    g_deferredWaterIBOffset = 0;
    g_deferredWaterOffsetBuf = nil;
    g_oitCmdsCount = 0;
    if (!g_currentPipeline && g_pipelineInhouse) {
      [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
      g_currentPipeline = g_pipelineInhouse;
      if (g_depthState)
        [g_currentEncoder setDepthStencilState:g_depthState];
    }
    if (!g_currentPipeline)
      return 0;
    float vp[16];
    for (int c = 0; c < 4; c++) {
      for (int r = 0; r < 4; r++) {
        float sum = 0;
        for (int k = 0; k < 4; k++) {
          sum += g_projMatrix[k * 4 + r] * g_mvMatrix[c * 4 + k];
        }
        vp[c * 4 + r] = sum;
      }
    }
    float frustumPlanes[24];
    extractFrustumPlanes(vp, frustumPlanes);
    float camX = g_camX, camY = g_camY, camZ = g_camZ;
    static const int VERTEX_STRIDE = 16;
    struct DrawCmd {
      uint64_t bufHandle;
      size_t megaOffset;
      id<MTLBuffer> resolvedBuf;

      int idxCount;
      int opaqueIdxCount;
      int opaqueFaceCounts[7];
      float distSq;
      float ox, oy, oz;
      bool isMega;
    };
    static DrawCmd *s_cmds = nullptr;
    static int s_cmdsCapacity = 0;

    int meshCount = g_activeMeshCount;
    if (meshCount == 0)
      return 0;
    if (s_cmdsCapacity < meshCount) {
      delete[] s_cmds;
      s_cmdsCapacity = meshCount * 2;
      s_cmds = new DrawCmd[s_cmdsCapacity];
    }
    int validCount = 0;
    int megaCount = 0;

    float baseDist = fmaxf(384.0f / g_dynamicLODScale,
                           fmaxf(256.0f, (float)g_configuredRenderDistBlocks));
    const float maxDrawDistSq = baseDist * baseDist;

    int totalActive = 0;

    struct MeshSnapshot {
      int meshIdx;
      float ox, oy, oz;
      uint64_t bufferHandle;
      int quadCount;
      int opaqueQuadCount;
      int opaqueFaceCounts[7];
    };
    static MeshSnapshot *s_snapshots = nullptr;
    static int s_snapshotsCap = 0;
    {
      std::shared_lock<std::shared_mutex> regLock(g_meshRegMutex);
      int activeTotal = (int)g_activeMeshIndices.size();
      if (s_snapshotsCap < activeTotal) {
        delete[] s_snapshots;
        s_snapshotsCap = activeTotal * 2;
        s_snapshots = new MeshSnapshot[s_snapshotsCap];
      }
      for (int k = 0; k < activeTotal; k++) {
        int i = g_activeMeshIndices[k];
        const NativeMesh &nm = g_nativeMeshes[i];

        if (__builtin_expect(nm.quadCount <= 0 || nm.bufferHandle == 0, 0))
          continue;
        float ox = nm.chunkX * 16.0f - camX;
        float oy = nm.chunkY * 16.0f - camY;
        float oz = nm.chunkZ * 16.0f - camZ;
        MeshSnapshot &snap = s_snapshots[totalActive++];
        snap.meshIdx = i;
        snap.ox = ox;
        snap.oy = oy;
        snap.oz = oz;
        snap.bufferHandle = nm.bufferHandle;
        snap.quadCount = nm.quadCount;
        snap.opaqueQuadCount = nm.opaqueQuadCount;
        memcpy(snap.opaqueFaceCounts, nm.facingQuadCounts,
               sizeof(snap.opaqueFaceCounts));
      }
    }

    if (totalActive == 0)
      return 0;

    if (s_cmdsCapacity < totalActive) {
      delete[] s_cmds;
      s_cmdsCapacity = totalActive * 2;
      s_cmds = new DrawCmd[s_cmdsCapacity];
    }

    int distCulled = 0;
    {
      std::shared_lock<std::shared_mutex> megaLock(g_megaMutex);
      std::shared_lock<std::shared_mutex> bufLock(g_bufferMutex);

      auto processVisible = [&](int si) {
        const MeshSnapshot &ms = s_snapshots[si];
        float cx = ms.ox + 8.0f, cy = ms.oy + 8.0f, cz = ms.oz + 8.0f;

        float distSq = cx * cx + cz * cz;
        if (__builtin_expect(distSq > maxDrawDistSq, 0)) {
          distCulled++;
          return;
        }
        int idxCount = ms.quadCount * 6;
        int opaqueIdxCount = ms.opaqueQuadCount * 6;
        bool mega = isMegaHandle(ms.bufferHandle);
        if (mega) {
          auto it = g_megaAllocs.find(ms.bufferHandle);
          if (__builtin_expect(it == g_megaAllocs.end(), 0))
            return;
          DrawCmd &cmd = s_cmds[validCount];
          cmd.bufHandle = ms.bufferHandle;
          cmd.megaOffset = it->second.offset;
          cmd.resolvedBuf = nil;
          cmd.idxCount = idxCount;
          cmd.opaqueIdxCount = opaqueIdxCount;
          memcpy(cmd.opaqueFaceCounts, ms.opaqueFaceCounts,
                 sizeof(cmd.opaqueFaceCounts));
          cmd.distSq = distSq;
          cmd.ox = ms.ox;
          cmd.oy = ms.oy;
          cmd.oz = ms.oz;
          cmd.isMega = true;
          megaCount++;
        } else {
          id<MTLBuffer> rb = nil;
          auto bit = g_buffers.find(ms.bufferHandle);
          if (bit != g_buffers.end())
            rb = bit->second;
          DrawCmd &cmd = s_cmds[validCount];
          cmd.bufHandle = ms.bufferHandle;
          cmd.megaOffset = 0;
          cmd.resolvedBuf = rb;
          cmd.idxCount = idxCount;
          cmd.opaqueIdxCount = opaqueIdxCount;
          memcpy(cmd.opaqueFaceCounts, ms.opaqueFaceCounts,
                 sizeof(cmd.opaqueFaceCounts));
          cmd.distSq = distSq;
          cmd.ox = ms.ox;
          cmd.oy = ms.oy;
          cmd.oz = ms.oz;
          cmd.isMega = false;
        }
        validCount++;
      };

      for (int si = 0; si < totalActive; si++) {
        const MeshSnapshot &ms = s_snapshots[si];
        if (!frustumTestAABB(frustumPlanes, ms.ox, ms.oy, ms.oz, ms.ox + 16.0f,
                             ms.oy + 16.0f, ms.oz + 16.0f))
          continue;
        processVisible(si);
      }

      bool severeUnderfill =
          (validCount > 0 && totalActive >= 96 &&
           (validCount <= 8 || validCount * 24 < totalActive));
      if (severeUnderfill) {
        validCount = 0;
        megaCount = 0;
        for (int si = 0; si < totalActive; si++) {
          processVisible(si);
        }
        if (g_frameCount < 5 || (g_frameCount % 600 == 0)) {
          dbg("CULL_FAILSAFE_LOW: visible=%d input=%d fallback distance-only\n",
              validCount, totalActive);
        }
      }

      if (validCount == 0 && totalActive > 0) {
        for (int si = 0; si < totalActive; si++) {
          processVisible(si);
        }
        if (g_frameCount < 5 || (g_frameCount % 600 == 0)) {
          dbg("CULL_FAILSAFE: frustum rejected all %d chunks, fallback "
              "distance-only\n",
              totalActive);
        }
      }
    }

    if (g_frameCount < 5 || (g_frameCount % 600 == 0)) {
      dbg("CULL: input=%d visible=%d frustumCulled=%d distCulled=%d\n",
          totalActive, validCount, totalActive - validCount - distCulled,
          distCulled);
    }

    if (validCount > 0) {

      if (g_staleCapacity < validCount) {
        free(g_staleDrawCmds);
        g_staleCapacity = validCount * 2;
        g_staleDrawCmds =
            (StaleDrawCmd *)malloc(sizeof(StaleDrawCmd) * g_staleCapacity);
      }
      for (int i = 0; i < validCount; i++) {
        StaleDrawCmd &stale = g_staleDrawCmds[i];
        stale.bufferHandle = s_cmds[i].bufHandle;
        stale.idxCount = s_cmds[i].idxCount;
        stale.opaqueIdxCount = s_cmds[i].opaqueIdxCount;
        memcpy(stale.opaqueFaceCounts, s_cmds[i].opaqueFaceCounts,
               sizeof(stale.opaqueFaceCounts));
        stale.ox = s_cmds[i].ox;
        stale.oy = s_cmds[i].oy;
        stale.oz = s_cmds[i].oz;
        stale.isMega = s_cmds[i].isMega;
      }
      g_staleDrawCount = validCount;
      g_staleMegaCount = megaCount;
      g_staleCamX = camX;
      g_staleCamY = camY;
      g_staleCamZ = camZ;
      g_hasStaleDrawList = true;
    } else if (g_hasStaleDrawList && g_staleDrawCount > 0) {

      dbg("use stale draw list (%d cmds)\n", g_staleDrawCount);
      float dcx = camX - g_staleCamX;
      float dcy = camY - g_staleCamY;
      float dcz = camZ - g_staleCamZ;
      validCount = g_staleDrawCount;
      megaCount = g_staleMegaCount;
      if (s_cmdsCapacity < validCount) {
        delete[] s_cmds;
        s_cmdsCapacity = validCount * 2;
        s_cmds = new DrawCmd[s_cmdsCapacity];
      }
      int reusedCount = 0;
      int reusedMegaCount = 0;
      for (int i = 0; i < validCount; i++) {
        const StaleDrawCmd &sc = g_staleDrawCmds[i];
        ResolvedBuf staleRes = resolve_buffer(sc.bufferHandle);
        if (!staleRes.buf)
          continue;
        DrawCmd &cmd = s_cmds[reusedCount++];
        cmd.bufHandle = sc.bufferHandle;
        cmd.megaOffset = staleRes.offset;
        cmd.resolvedBuf = staleRes.buf;
        cmd.idxCount = sc.idxCount;
        cmd.opaqueIdxCount = sc.opaqueIdxCount;
        memcpy(cmd.opaqueFaceCounts, sc.opaqueFaceCounts,
               sizeof(cmd.opaqueFaceCounts));
        cmd.distSq = 0.0f;
        cmd.ox = sc.ox - dcx;
        cmd.oy = sc.oy - dcy;
        cmd.oz = sc.oz - dcz;
        cmd.isMega = sc.isMega;
        if (sc.isMega)
          reusedMegaCount++;
      }
      validCount = reusedCount;
      megaCount = reusedMegaCount;
    }

    if (validCount == 0)
      return 0;

    if (validCount > 1) {
      static DrawCmd *s_radixScratch = nullptr;
      static int s_radixScratchCap = 0;
      if (s_radixScratchCap < validCount) {
        delete[] s_radixScratch;
        s_radixScratchCap = std::max(validCount * 2, 1024);
        s_radixScratch = new DrawCmd[s_radixScratchCap];
      }
      if (validCount <= 64) {

        std::sort(s_cmds, s_cmds + validCount,
                  [](const DrawCmd &a, const DrawCmd &b) {
                    return a.distSq < b.distSq;
                  });
      } else {
        DrawCmd *src = s_cmds, *dst = s_radixScratch;
        int counts[256];
        for (int pass = 0; pass < 4; pass++) {
          int shift = pass * 8;
          memset(counts, 0, sizeof(counts));
          for (int i = 0; i < validCount; i++) {
            uint32_t key;
            memcpy(&key, &src[i].distSq, sizeof(uint32_t));
            counts[(key >> shift) & 0xFF]++;
          }

          bool skip = false;
          for (int b = 0; b < 256; b++) {
            if (counts[b] == validCount) {
              skip = true;
              break;
            }
          }
          if (skip)
            continue;

          int total = 0;
          for (int b = 0; b < 256; b++) {
            int c = counts[b];
            counts[b] = total;
            total += c;
          }

          for (int i = 0; i < validCount; i++) {
            uint32_t key;
            memcpy(&key, &src[i].distSq, sizeof(uint32_t));
            dst[counts[(key >> shift) & 0xFF]++] = src[i];
          }
          std::swap(src, dst);
        }

        if (src != s_cmds) {
          memcpy(s_cmds, src, (size_t)validCount * sizeof(DrawCmd));
        }
      }
    }

    if (g_meshShadersActive && g_pipelineMeshOpaque && megaCount > 0 &&
        g_megaVB && g_blockAtlas && g_tripleBuffers[g_renderSlot] &&
        g_tripleBuffers[g_renderSlot].length >= sizeof(CameraUniformsCPU)) {

      CameraUniformsCPU *cu =
          (CameraUniformsCPU *)[g_tripleBuffers[g_renderSlot] contents];
      memcpy(cu->viewProjection, vp, sizeof(vp));
      memcpy(cu->projection, g_projMatrix, sizeof(g_projMatrix));
      memcpy(cu->modelView, g_mvMatrix, sizeof(g_mvMatrix));

      cu->cameraPosition[0] = (float)g_camX;
      cu->cameraPosition[1] = (float)g_camY;
      cu->cameraPosition[2] = (float)g_camZ;
      cu->cameraPosition[3] = g_skyBrightness;
      memcpy(cu->frustumPlanes, frustumPlanes, sizeof(frustumPlanes));
      cu->screenSize[0] = 0.0f;
      cu->screenSize[1] = 0.0f;
      cu->nearPlane = 0.05f;
      cu->farPlane = 1024.0f;
      cu->frameIndex = (uint32_t)g_frameCount;
      cu->hizMipCount = 0;
      cu->totalChunks = 0;

      cu->waterFog = g_entityOverlayParams[2];

      int meshletSlot = g_renderSlot % kTripleBufferCount;
      size_t meshletBufNeeded = (size_t)validCount * sizeof(ChunkMeshletNative);
      if (!g_meshletBuffers[meshletSlot] ||
          g_meshletBuffers[meshletSlot].length < meshletBufNeeded) {
        if (g_meshletBuffers[meshletSlot])
          [g_meshletBuffers[meshletSlot] release];
        g_meshletBuffers[meshletSlot] =
            [g_device newBufferWithLength:meshletBufNeeded * 2
                                  options:MTLStorageModeShared];
      }
      id<MTLBuffer> meshletBuf = g_meshletBuffers[meshletSlot];

      int meshletCount = 0;
      static const int VERTEX_STRIDE_MESH = 16;
      if (meshletBuf) {
        ChunkMeshletNative *meshlets =
            (ChunkMeshletNative *)[meshletBuf contents];
        for (int i = 0; i < validCount; i++) {
          if (!s_cmds[i].isMega)
            continue;
          int opaqueV = (s_cmds[i].opaqueIdxCount / 6) * 4;
          if (opaqueV <= 0)
            continue;
          meshlets[meshletCount].baseVertexOffset =
              (uint32_t)(s_cmds[i].megaOffset / VERTEX_STRIDE_MESH);
          meshlets[meshletCount].vertexCount = (uint32_t)opaqueV;
          meshlets[meshletCount].worldX = s_cmds[i].ox;
          meshlets[meshletCount].worldY = s_cmds[i].oy;
          meshlets[meshletCount].worldZ = s_cmds[i].oz;
          meshlets[meshletCount]._pad0 = 0;
          meshlets[meshletCount]._pad1 = 0;
          meshletCount++;
        }
        cu->totalChunks = (uint32_t)meshletCount;
      }

      if (meshletCount > 0) {
        if (@available(macOS 13.0, *)) {
          [g_currentEncoder setRenderPipelineState:g_pipelineMeshOpaque];
          g_currentPipeline = g_pipelineMeshOpaque;
          if (g_depthState)
            [g_currentEncoder setDepthStencilState:g_depthState];

          id<MTLBuffer> camBuf = g_tripleBuffers[g_renderSlot];

          [g_currentEncoder setObjectBuffer:meshletBuf offset:0 atIndex:0];
          [g_currentEncoder setObjectBuffer:camBuf offset:0 atIndex:1];

          [g_currentEncoder setMeshBuffer:meshletBuf offset:0 atIndex:0];
          [g_currentEncoder setMeshBuffer:camBuf offset:0 atIndex:1];
          [g_currentEncoder setMeshBuffer:g_megaVB offset:0 atIndex:2];

          [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
          [g_currentEncoder setFragmentBuffer:camBuf offset:0 atIndex:1];

          MTLSize objTGS = MTLSizeMake((NSUInteger)meshletCount, 1, 1);
          MTLSize objTPG = MTLSizeMake(1, 1, 1);
          MTLSize meshTPG = MTLSizeMake(256, 1, 1);

          [g_currentEncoder drawMeshThreadgroups:objTGS
                     threadsPerObjectThreadgroup:objTPG
                       threadsPerMeshThreadgroup:meshTPG];
          g_drawCallCount++;

          g_currentPipeline = nil;
        }
      }

      if (g_useProgrammableBlending ||
          (g_pipelineInhouse && g_depthStateNoWrite &&
           g_thermalQualityLevel < 2)) {

        static id<MTLBuffer> s_waterOffsetBuf[kTripleBufferCount] = {};
        static size_t s_waterOffsetCap = 0;
        size_t waterOfBufNeeded = (size_t)validCount * 16;
        if (s_waterOffsetCap < waterOfBufNeeded) {
          for (int wb = 0; wb < kTripleBufferCount; wb++) {
            if (s_waterOffsetBuf[wb])
              [s_waterOffsetBuf[wb] release];
            s_waterOffsetBuf[wb] = [g_device
                newBufferWithLength:waterOfBufNeeded * 2
                            options:MTLStorageModeShared |
                                    MTLResourceCPUCacheModeWriteCombined];
          }
          s_waterOffsetCap = waterOfBufNeeded * 2;
        }
        id<MTLBuffer> waterBuf = s_waterOffsetBuf[g_renderSlot];
        if (waterBuf) {
          float *offBuf = (float *)[waterBuf contents];
          for (int i = 0; i < validCount; i++) {
            offBuf[i * 4 + 0] = s_cmds[i].ox;
            offBuf[i * 4 + 1] = s_cmds[i].oy;
            offBuf[i * 4 + 2] = s_cmds[i].oz;
            uint32_t fm = 0u;
            memcpy(&offBuf[i * 4 + 3], &fm, 4);
          }

          {
            id<MTLRenderPipelineState> opaqueP = g_pipelineInhouseOpaque
                                                     ? g_pipelineInhouseOpaque
                                                     : g_pipelineInhouse;
            if (opaqueP) {
              [g_currentEncoder setRenderPipelineState:opaqueP];
              g_currentPipeline = opaqueP;
              if (g_blockAtlas)
                [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
              if (g_depthState)
                [g_currentEncoder setDepthStencilState:g_depthState];
              [g_currentEncoder
                  setFrontFacingWinding:MTLWindingCounterClockwise];
              [g_currentEncoder setCullMode:MTLCullModeBack];
              [g_currentEncoder setVertexBytes:g_projMatrix
                                        length:64
                                       atIndex:1];
              [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
              float camPosNM[4] = {0.0f, 0.0f, 0.0f, g_skyBrightness};
              [g_currentEncoder setVertexBytes:camPosNM length:16 atIndex:3];
              [g_currentEncoder setVertexBuffer:waterBuf offset:0 atIndex:4];
              [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                                          length:sizeof(g_entityOverlayParams)
                                         atIndex:5];
              for (int i = 0; i < validCount; i++) {
                if (s_cmds[i].isMega)
                  continue;
                if (s_cmds[i].opaqueIdxCount <= 0)
                  continue;
                if (!s_cmds[i].resolvedBuf)
                  continue;
                [g_currentEncoder setVertexBuffer:s_cmds[i].resolvedBuf
                                           offset:0
                                          atIndex:0];
                [g_currentEncoder
                    drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                               indexCount:(NSUInteger)s_cmds[i].opaqueIdxCount
                                indexType:MTLIndexTypeUInt32
                              indexBuffer:ib
                        indexBufferOffset:ibOffset
                            instanceCount:1
                               baseVertex:0
                             baseInstance:(NSUInteger)i];
                g_drawCallCount++;
              }
              if (g_megaVB)
                [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
            }
          }

          if (g_useProgrammableBlending) {
            if (g_oitCmdsCapacity < validCount) {
              free(g_oitCmds);
              g_oitCmdsCapacity = validCount * 2;
              g_oitCmds = (OITCachedCmd *)malloc(sizeof(OITCachedCmd) *
                                                 g_oitCmdsCapacity);
            }
            int oitCount = 0;
            for (int i = 0; i < validCount; i++) {
              int waterIdxCount = s_cmds[i].idxCount - s_cmds[i].opaqueIdxCount;
              if (waterIdxCount <= 0)
                continue;
              g_oitCmds[oitCount++] = {
                  s_cmds[i].resolvedBuf,
                  s_cmds[i].megaOffset,
                  s_cmds[i].isMega,
                  waterIdxCount,
                  i,
                  s_cmds[i].opaqueIdxCount / 6 * 4,
              };
            }
            g_oitCmdsCount = oitCount;
            g_oitIB = ib;
            g_oitIBOffset = ibOffset;
            g_oitOffsetBuf = waterBuf;
          } else {
            if (g_deferredWaterCapacity < validCount) {
              free(g_deferredWaterCmds);
              g_deferredWaterCapacity = validCount * 2;
              g_deferredWaterCmds = (DeferredWaterCmd *)malloc(
                  sizeof(DeferredWaterCmd) * g_deferredWaterCapacity);
            }
            int deferredCount = 0;
            for (int i = 0; i < validCount; i++) {
              int waterIdxCount = s_cmds[i].idxCount - s_cmds[i].opaqueIdxCount;
              if (waterIdxCount <= 0)
                continue;
              g_deferredWaterCmds[deferredCount++] = {
                  s_cmds[i].resolvedBuf, s_cmds[i].megaOffset,
                  s_cmds[i].idxCount,    s_cmds[i].opaqueIdxCount,
                  s_cmds[i].distSq,      i,
                  s_cmds[i].isMega,
              };
            }
            g_deferredWaterCmdCount = deferredCount;
            g_deferredWaterIB = ib;
            g_deferredWaterIBOffset = ibOffset;
            g_deferredWaterOffsetBuf = waterBuf;
          }
          if (g_depthState)
            [g_currentEncoder setDepthStencilState:g_depthState];

          [g_currentEncoder setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
          [g_currentEncoder setCullMode:MTLCullModeBack];
          g_currentPipeline = nil;
        }
      }

      return (jint)meshletCount;
    }

    static id<MTLBuffer> g_v18OffsetRing[kTripleBufferCount] = {};
    static size_t g_v18OffsetRingCap = 0;
    static const size_t OFFSET_RING_MIN_CAP = 16384 * 16;
    size_t offsetBufSize = (size_t)validCount * 16;
    if (g_v18OffsetRingCap < offsetBufSize) {
      size_t newCap = std::max(OFFSET_RING_MIN_CAP, offsetBufSize * 2);
      for (int rb = 0; rb < kTripleBufferCount; rb++) {
        if (g_v18OffsetRing[rb])
          [g_v18OffsetRing[rb] release];
        g_v18OffsetRing[rb] =
            [g_device newBufferWithLength:newCap
                                  options:MTLStorageModeShared |
                                          MTLResourceCPUCacheModeWriteCombined];
      }
      g_v18OffsetRingCap = newCap;
      dbg("GAP1: Allocated %d offset ring buffers, %zuKB each "
          "(WriteCombined)\n",
          kTripleBufferCount, newCap / 1024);
    }
    id<MTLBuffer> offsetBuf = g_v18OffsetRing[g_renderSlot];
    float *offBuf = (float *)[offsetBuf contents];
    int totalOpaqueIdx = 0;
    for (int i = 0; i < validCount; i++) {
      if (i + 8 < validCount)
        __builtin_prefetch(&s_cmds[i + 8], 0, 1);
      totalOpaqueIdx += s_cmds[i].opaqueIdxCount;
      offBuf[i * 4 + 0] = s_cmds[i].ox;
      offBuf[i * 4 + 1] = s_cmds[i].oy;
      offBuf[i * 4 + 2] = s_cmds[i].oz;
      uint32_t faceMask = 0u;
      float maskAsFloat;
      memcpy(&maskAsFloat, &faceMask, sizeof(float));
      offBuf[i * 4 + 3] = maskAsFloat;
    }
    if (g_megaVB) {
      [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
    }
    [g_currentEncoder setVertexBuffer:offsetBuf offset:0 atIndex:4];

    if (g_depthState) {
      [g_currentEncoder setDepthStencilState:g_depthState];
    }
    [g_currentEncoder setFrontFacingWinding:MTLWindingCounterClockwise];
    [g_currentEncoder setCullMode:MTLCullModeBack];

    [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                                length:sizeof(g_entityOverlayParams)
                               atIndex:5];

    if (g_useProgrammableBlending) {
      if (g_oitCmdsCapacity < validCount) {
        free(g_oitCmds);
        g_oitCmdsCapacity = validCount * 2;
        g_oitCmds =
            (OITCachedCmd *)malloc(sizeof(OITCachedCmd) * g_oitCmdsCapacity);
      }
      int oitCount = 0;
      for (int i = 0; i < validCount; i++) {
        int wIdx = s_cmds[i].idxCount - s_cmds[i].opaqueIdxCount;
        if (wIdx <= 0)
          continue;
        g_oitCmds[oitCount++] = {
            s_cmds[i].resolvedBuf,
            s_cmds[i].megaOffset,
            s_cmds[i].isMega,
            wIdx,
            i,
            s_cmds[i].opaqueIdxCount / 6 * 4,
        };
      }
      g_oitCmdsCount = oitCount;
      g_oitIB = ib;
      g_oitIBOffset = ibOffset;
      g_oitOffsetBuf = offsetBuf;
    } else if (g_depthStateNoWrite && g_thermalQualityLevel < 2) {
      if (g_deferredWaterCapacity < validCount) {
        free(g_deferredWaterCmds);
        g_deferredWaterCapacity = validCount * 2;
        g_deferredWaterCmds = (DeferredWaterCmd *)malloc(
            sizeof(DeferredWaterCmd) * g_deferredWaterCapacity);
      }
      int deferredCount = 0;
      for (int i = 0; i < validCount; i++) {
        int waterIdxCount = s_cmds[i].idxCount - s_cmds[i].opaqueIdxCount;
        if (waterIdxCount <= 0)
          continue;
        g_deferredWaterCmds[deferredCount++] = {
            s_cmds[i].resolvedBuf,    s_cmds[i].megaOffset, s_cmds[i].idxCount,
            s_cmds[i].opaqueIdxCount, s_cmds[i].distSq,     i,
            s_cmds[i].isMega,
        };
      }
      g_deferredWaterCmdCount = deferredCount;
      g_deferredWaterIB = ib;
      g_deferredWaterIBOffset = ibOffset;
      g_deferredWaterOffsetBuf = offsetBuf;
    }

    int icbCandidateCount = 0;
    for (int i = 0; i < validCount; i++) {
      if (s_cmds[i].opaqueIdxCount <= 0)
        continue;
      if (s_cmds[i].isMega) {
        if (!g_megaVB)
          continue;
      } else if (!s_cmds[i].resolvedBuf) {
        continue;
      }
      icbCandidateCount += visibleOpaqueBucketCount(
          s_cmds[i].opaqueFaceCounts,
          visibleFacingMaskForAabb(s_cmds[i].ox, s_cmds[i].oy, s_cmds[i].oz));
    }
    bool canICB =
        (g_gpuDrivenEnabled && g_icbCapable && icbCandidateCount > 0 &&
         g_pipelineInhouseICB && g_fragArgBuf[g_renderSlot] &&
         g_fragArgEncoder && g_blockAtlas && g_lightmap);
    bool useOpaqueICB = canICB && g_pipelineInhouseICBOpaque &&
                        g_fragArgBufOpaque[g_renderSlot] &&
                        g_fragArgEncoderOpaque;
    bool useOpaque = g_pipelineInhouseOpaque != nil;
    if (canICB) {
      if (useOpaqueICB) {
        [g_currentEncoder setRenderPipelineState:g_pipelineInhouseICBOpaque];
        g_currentPipeline = g_pipelineInhouseICBOpaque;
        [g_fragArgEncoderOpaque
            setArgumentBuffer:g_fragArgBufOpaque[g_renderSlot]
                       offset:0];
        [g_fragArgEncoderOpaque setTexture:g_blockAtlas atIndex:0];
        [g_fragArgEncoderOpaque setTexture:g_lightmap atIndex:1];
        [g_currentEncoder setFragmentBuffer:g_fragArgBufOpaque[g_renderSlot]
                                     offset:0
                                    atIndex:0];
      } else {
        [g_currentEncoder setRenderPipelineState:g_pipelineInhouseICB];
        g_currentPipeline = g_pipelineInhouseICB;
        [g_fragArgEncoder setArgumentBuffer:g_fragArgBuf[g_renderSlot]
                                     offset:0];
        [g_fragArgEncoder setTexture:g_blockAtlas atIndex:0];
        [g_fragArgEncoder setTexture:g_lightmap atIndex:1];
        [g_currentEncoder setFragmentBuffer:g_fragArgBuf[g_renderSlot]
                                     offset:0
                                    atIndex:0];
      }
      [g_currentEncoder useResource:g_blockAtlas
                              usage:MTLResourceUsageRead
                             stages:MTLRenderStageFragment];
      [g_currentEncoder useResource:g_lightmap
                              usage:MTLResourceUsageRead
                             stages:MTLRenderStageFragment];
      if (!g_icb[g_renderSlot] ||
          g_icbMaxCommands[g_renderSlot] < (NSUInteger)icbCandidateCount) {
        NSUInteger newSize =
            MAX(ICB_INITIAL_SIZE, (NSUInteger)icbCandidateCount * 2);
        MTLIndirectCommandBufferDescriptor *desc =
            [MTLIndirectCommandBufferDescriptor new];
        desc.commandTypes = MTLIndirectCommandTypeDrawIndexed;
        desc.inheritPipelineState = YES;
        desc.inheritBuffers = YES;
        desc.maxVertexBufferBindCount = 1;

        if (g_icb[g_renderSlot])
          [g_icb[g_renderSlot] release];
        g_icb[g_renderSlot] = [g_device
            newIndirectCommandBufferWithDescriptor:desc
                                   maxCommandCount:newSize
                                           options:MTLStorageModeShared];
        [desc release];
        g_icbMaxCommands[g_renderSlot] = newSize;
      }
      [g_icb[g_renderSlot]
          resetWithRange:NSMakeRange(0, (NSUInteger)icbCandidateCount)];
      int icbIdx = 0;
      for (int i = 0; i < validCount; i++) {
        int opaqueIdx = s_cmds[i].opaqueIdxCount;
        if (__builtin_expect(opaqueIdx <= 0, 0))
          continue;
        if (s_cmds[i].isMega) {
          if (__builtin_expect(!g_megaVB, 0))
            continue;
        } else {
          if (__builtin_expect(s_cmds[i].resolvedBuf == nil, 0))
            continue;
        }
        uint32_t faceMask =
            visibleFacingMaskForAabb(s_cmds[i].ox, s_cmds[i].oy, s_cmds[i].oz);
        int baseQuad = 0;
        for (int face = 0; face < 7; face++) {
          int quadCount = s_cmds[i].opaqueFaceCounts[face];
          if (quadCount <= 0) {
            continue;
          }
          if ((faceMask & (1u << face)) == 0) {
            baseQuad += quadCount;
            continue;
          }
          id<MTLIndirectRenderCommand> icmd = [g_icb[g_renderSlot]
              indirectRenderCommandAtIndex:(NSUInteger)icbIdx];
          if (!s_cmds[i].isMega) {
            [icmd setVertexBuffer:s_cmds[i].resolvedBuf offset:0 atIndex:0];
          }
          NSInteger baseVertex =
              (NSInteger)(s_cmds[i].isMega
                              ? (s_cmds[i].megaOffset / VERTEX_STRIDE)
                              : 0) +
              baseQuad * 4;
          [icmd drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                           indexCount:(NSUInteger)(quadCount * 6)
                            indexType:MTLIndexTypeUInt32
                          indexBuffer:ib
                    indexBufferOffset:ibOffset
                        instanceCount:1
                           baseVertex:baseVertex
                         baseInstance:(NSUInteger)i];
          icbIdx++;
          baseQuad += quadCount;
        }
      }
      [g_currentEncoder
          executeCommandsInBuffer:g_icb[g_renderSlot]
                        withRange:NSMakeRange(0, (NSUInteger)icbIdx)];
      g_drawCallCount++;
      if (useOpaque) {
        [g_currentEncoder setRenderPipelineState:g_pipelineInhouseOpaque];
        g_currentPipeline = g_pipelineInhouseOpaque;
      } else {
        [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
        g_currentPipeline = g_pipelineInhouse;
      }
      if (g_blockAtlas) {
        [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
      }
      if (g_megaVB) {
        [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
      }
    } else {
      if (useOpaque) {
        [g_currentEncoder setRenderPipelineState:g_pipelineInhouseOpaque];
        g_currentPipeline = g_pipelineInhouseOpaque;
        if (g_blockAtlas) {
          [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
        }
      }
      if (g_megaVB) {
        [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
      }
      bool lastVB0Mega = (g_megaVB != nil);
      for (int i = 0; i < validCount; i++) {
        int opaqueIdx = s_cmds[i].opaqueIdxCount;
        if (__builtin_expect(opaqueIdx <= 0, 0))
          continue;
        uint32_t faceMask =
            visibleFacingMaskForAabb(s_cmds[i].ox, s_cmds[i].oy, s_cmds[i].oz);
        int baseQuad = 0;
        if (__builtin_expect(s_cmds[i].isMega, 1)) {
          if (__builtin_expect(!lastVB0Mega, 0)) {
            [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
            lastVB0Mega = true;
          }
          for (int face = 0; face < 7; face++) {
            int quadCount = s_cmds[i].opaqueFaceCounts[face];
            if (quadCount <= 0) {
              continue;
            }
            if ((faceMask & (1u << face)) == 0) {
              baseQuad += quadCount;
              continue;
            }
            [g_currentEncoder
                drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                           indexCount:(NSUInteger)(quadCount * 6)
                            indexType:MTLIndexTypeUInt32
                          indexBuffer:ib
                    indexBufferOffset:ibOffset
                        instanceCount:1
                           baseVertex:(NSInteger)(s_cmds[i].megaOffset /
                                                  VERTEX_STRIDE) +
                                      baseQuad * 4
                         baseInstance:(NSUInteger)i];
            baseQuad += quadCount;
            g_drawCallCount++;
          }
        } else {
          if (__builtin_expect(s_cmds[i].resolvedBuf != nil, 1)) {
            [g_currentEncoder setVertexBuffer:s_cmds[i].resolvedBuf
                                       offset:0
                                      atIndex:0];
            lastVB0Mega = false;
            for (int face = 0; face < 7; face++) {
              int quadCount = s_cmds[i].opaqueFaceCounts[face];
              if (quadCount <= 0) {
                continue;
              }
              if ((faceMask & (1u << face)) == 0) {
                baseQuad += quadCount;
                continue;
              }
              [g_currentEncoder
                  drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                             indexCount:(NSUInteger)(quadCount * 6)
                              indexType:MTLIndexTypeUInt32
                            indexBuffer:ib
                      indexBufferOffset:ibOffset
                          instanceCount:1
                             baseVertex:baseQuad * 4
                           baseInstance:(NSUInteger)i];
              baseQuad += quadCount;
              g_drawCallCount++;
            }
          }
        }
      }
    }
    int waterDraws =
        g_useProgrammableBlending ? g_oitCmdsCount : g_deferredWaterCmdCount;
    if (g_frameCount < 5 || g_frameCount % 600 == 0) {
      float gpuMs2 = g_lastGpuMs.load(std::memory_order_relaxed);
      dbg("V18_Draw: meshes=%d visible=%d mega=%d icb=%s gpu=%.1fms "
          "tris=%dK water=%d\n",
          g_activeMeshCount, validCount, megaCount, canICB ? "Y" : "N", gpuMs2,
          totalOpaqueIdx / 3000, waterDraws);
    }
    g_prof_drawAll_acc += (mach_absolute_time() - _prof_t0);
    return (jint)validCount;
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer, jint vertexCount,
    jint baseVertex) {
  (void)frameContext;
  if (!g_currentEncoder || vertexCount <= 0)
    return;
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  if (!vbRes.buf)
    return;
  if (!g_currentPipeline && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
  if (!g_currentPipeline)
    return;
  [g_currentEncoder setVertexBuffer:vbRes.buf
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                       vertexStart:(NSUInteger)baseVertex
                       vertexCount:(NSUInteger)vertexCount];
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetDebugColor(
    JNIEnv *, jclass, jlong frameContext, jfloat r, jfloat g, jfloat b,
    jfloat a) {
  (void)frameContext;
  if (!g_currentEncoder)
    return;
  float color[4] = {r, g, b, a};
  [g_currentEncoder setVertexBytes:color length:sizeof(color) atIndex:5];
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawLineBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer,
    jint vertexCount) {
  (void)frameContext;
  if (__builtin_expect(g_frameCount < 5, 0))
    dbg("nDrawLineBuffer: encoder=%p, vtxCount=%d, pipeline=%p, buffer=%lld\n",
        g_currentEncoder, vertexCount, g_pipelineDebugLines,
        (long long)vertexBuffer);
  if (!g_currentEncoder || vertexCount <= 0 || !g_pipelineDebugLines)
    return;
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  if (!vbRes.buf) {
    if (__builtin_expect(g_frameCount < 5, 0))
      dbg("nDrawLineBuffer: buffer lookup failed\n");
    return;
  }
  [g_currentEncoder setRenderPipelineState:g_pipelineDebugLines];
  if (g_depthStateLessEqual)
    [g_currentEncoder setDepthStencilState:g_depthStateLessEqual];
  [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
  [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
  [g_currentEncoder setVertexBuffer:vbRes.buf
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeLine
                       vertexStart:0
                       vertexCount:(NSUInteger)vertexCount];
  if (__builtin_expect(g_frameCount < 5, 0))
    dbg("nDrawLineBuffer: drew %d vertices\n", vertexCount);
  if (g_currentPipeline) {
    [g_currentEncoder setRenderPipelineState:g_currentPipeline];
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawTriangleBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer,
    jint vertexCount) {
  (void)frameContext;
  if (!g_currentEncoder || vertexCount <= 0 || !g_pipelineDebugLines)
    return;
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  if (!vbRes.buf)
    return;
  [g_currentEncoder setRenderPipelineState:g_pipelineDebugLines];
  if (g_depthStateLessEqual)
    [g_currentEncoder setDepthStencilState:g_depthStateLessEqual];
  [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
  [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
  [g_currentEncoder setVertexBuffer:vbRes.buf
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                       vertexStart:0
                       vertexCount:(NSUInteger)vertexCount];
  if (g_currentPipeline) {
    [g_currentEncoder setRenderPipelineState:g_currentPipeline];
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawOverlayQuad(
    JNIEnv *, jclass, jlong frameContext, jfloat r, jfloat g, jfloat b,
    jfloat a) {
  (void)frameContext;
  if (!g_currentEncoder || !g_pipelineDebugLines)
    return;

  float identity[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  float color[4] = {r, g, b, a};

  float verts[9] = {
      -1.0f, -1.0f, 0.5f, 3.0f, -1.0f, 0.5f, -1.0f, 3.0f, 0.5f,
  };

  [g_currentEncoder setRenderPipelineState:g_pipelineDebugLines];

  if (g_depthStateNoWrite)
    [g_currentEncoder setDepthStencilState:g_depthStateNoWrite];

  [g_currentEncoder setVertexBytes:identity length:64 atIndex:1];
  [g_currentEncoder setVertexBytes:identity length:64 atIndex:2];
  [g_currentEncoder setVertexBytes:color length:16 atIndex:5];
  [g_currentEncoder setVertexBytes:verts length:sizeof(verts) atIndex:0];
  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                       vertexStart:0
                       vertexCount:3];

  if (g_currentPipeline) {
    [g_currentEncoder setRenderPipelineState:g_currentPipeline];
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetCurrentFrameContext(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  if (!g_device || !g_queue || !g_tbColor[0] || !g_tbDepth[0])
    return 0;
  if (g_gpuNeedsRecovery.load(std::memory_order_acquire)) {
    g_gpuNeedsRecovery.store(false, std::memory_order_release);
    dbg("GPU_RECOVERY: draining resources before recreating command queue\n");
    if (g_currentEncoder) {
      [g_currentEncoder endEncoding];
      [g_currentEncoder release];
      g_currentEncoder = nil;
    }
    if (g_currentCmdBuffer) {
      [g_currentCmdBuffer commit];
      [g_currentCmdBuffer waitUntilCompleted];
      [g_currentCmdBuffer release];
      g_currentCmdBuffer = nil;
    }
    drain_surface_slots(true);
    [g_queue release];
    g_queue = [g_device newCommandQueue];
    g_queue.label = @"MetalRender Metal 3 compatibility queue";
  }
  if (g_currentEncoder)
    return (jlong)0x1;
  @autoreleasepool {
    bool reuseFrame = false;
    if (g_reuseTerrainFrame) {
      static bool loggedUnsafeReuse = false;
      if (!loggedUnsafeReuse) {
        dbg("Terrain frame reuse disabled: an IOSurface bound to OpenGL must "
            "not be modified by Metal\n");
        loggedUnsafeReuse = true;
      }
    }
    g_reuseTerrainFrame = false;
    g_wasReuseFrame = false;

    bool semaphoreAcquired = false;
    if (g_frameSemaphore &&
        !g_shuttingDown.load(std::memory_order_acquire)) {
      // This wait is reached only after all triple-buffered frames are busy.
      // At high refresh rates the old 8 ms floor could expire before one
      // legitimate shader-heavy GPU frame completed, turning normal back
      // pressure into a dropped frame. Keep the wait bounded, but scale it to
      // the configured cadence and the depth of the in-flight queue.
      int waitMs = std::clamp(
          (int)ceilf(g_targetFrameTimeMs * 3.0f), 32, 100);
      dispatch_time_t softTimeout =
          dispatch_time(DISPATCH_TIME_NOW, (int64_t)waitMs * NSEC_PER_MSEC);
      if (dispatch_semaphore_wait(g_frameSemaphore, softTimeout) != 0) {
        // A fullscreen/resize transition or temporary driver scheduling stall
        // can exceed the normal frame-cadence window even though the command
        // buffer is healthy.  Do not turn that transient back-pressure into a
        // dropped Metal frame.  Completion handlers always signal the
        // semaphore (including failed command buffers), so continue with a
        // bounded hard wait and classify only that deadline as a native fault.
        constexpr int kHardInFlightWaitMs = 1000;
        int remainingMs = std::max(1, kHardInFlightWaitMs - waitMs);
        dispatch_time_t hardTimeout = dispatch_time(
            DISPATCH_TIME_NOW, (int64_t)remainingMs * NSEC_PER_MSEC);
        if (dispatch_semaphore_wait(g_frameSemaphore, hardTimeout) != 0) {
          g_inFlightFrameTimeoutCount.fetch_add(1,
                                                std::memory_order_relaxed);
          static int timeoutCount = 0;
          if (++timeoutCount <= 10 || timeoutCount % 100 == 0)
            dbg("Frame skipped: in-flight GPU hard timeout (%d)\n",
                timeoutCount);
          return 0;
        }
        static int recoveredBackpressureCount = 0;
        if (++recoveredBackpressureCount <= 10
            || recoveredBackpressureCount % 100 == 0) {
          dbg("Frame pacing recovered after soft in-flight wait (%d)\n",
              recoveredBackpressureCount);
        }
      }
      semaphoreAcquired = true;
    }

    int acquiredSlot = acquire_surface_slot(g_currentBufferIndex);
    if (acquiredSlot < 0) {
      g_noIOSurfaceSlotSkipCount.fetch_add(1, std::memory_order_relaxed);
      if (semaphoreAcquired && g_frameSemaphore)
        dispatch_semaphore_signal(g_frameSemaphore);
      static int noSlotCount = 0;
      if (++noSlotCount <= 10 || noSlotCount % 100 == 0)
        dbg("Frame skipped: no IOSurface slot is safe for Metal (%d)\n",
            noSlotCount);
      return 0;
    }
    g_renderSlot = acquiredSlot;
    g_color = g_tbColor[g_renderSlot];
    g_depth = g_tbDepth[g_renderSlot];
    g_ioSurface = g_tbIOSurface[g_renderSlot];
    g_currentFrameReady.store(false, std::memory_order_release);
    g_currentCmdBuffer = [[g_queue commandBuffer] retain];
    if (!g_currentCmdBuffer) {
      g_tbSlotState[g_renderSlot].store(SurfaceSlotAvailable,
                                        std::memory_order_release);
      g_tbSlotReady[g_renderSlot].store(true, std::memory_order_release);
      g_surfaceSlotChanged.notify_all();
      if (semaphoreAcquired && g_frameSemaphore)
        dispatch_semaphore_signal(g_frameSemaphore);
      return 0;
    }
    static MTLRenderPassDescriptor *s_cachedRP = nil;
    if (!s_cachedRP) {
      s_cachedRP = [[MTLRenderPassDescriptor renderPassDescriptor] retain];
      s_cachedRP.colorAttachments[0].storeAction = MTLStoreActionStore;
      s_cachedRP.colorAttachments[0].clearColor =
          MTLClearColorMake(0.0, 0.0, 0.0, 0.0);

      s_cachedRP.depthAttachment.storeAction = MTLStoreActionStore;
      s_cachedRP.depthAttachment.clearDepth = 0.0;
    }
#ifdef METALRENDER_HAS_METALFX

    id<MTLTexture> renderTarget = g_color;
    id<MTLTexture> depthTarget = g_depth;
    bool usingLR = false;
    if (@available(macOS 13.0, *)) {
      if (g_mfxScaler && g_lrColor[g_renderSlot]) {
        renderTarget = g_lrColor[g_renderSlot];
        depthTarget = g_lrDepth[g_renderSlot];
        usingLR = true;
      }
    }
    g_frameColorTarget = renderTarget;
    g_frameDepthTarget = depthTarget;
    g_wasLowResolutionFrame = usingLR;
    s_cachedRP.colorAttachments[0].texture = renderTarget;
    s_cachedRP.colorAttachments[0].loadAction =
        (reuseFrame && !usingLR) ? MTLLoadActionLoad : MTLLoadActionClear;
    s_cachedRP.depthAttachment.texture = depthTarget;
    s_cachedRP.depthAttachment.loadAction =
        (reuseFrame && !usingLR) ? MTLLoadActionLoad : MTLLoadActionClear;
#else
    g_frameColorTarget = g_color;
    g_frameDepthTarget = g_depth;
    g_wasLowResolutionFrame = false;
    s_cachedRP.colorAttachments[0].texture = g_color;
    s_cachedRP.colorAttachments[0].loadAction =
        reuseFrame ? MTLLoadActionLoad : MTLLoadActionClear;
    s_cachedRP.depthAttachment.texture = g_depth;
    s_cachedRP.depthAttachment.loadAction =
        reuseFrame ? MTLLoadActionLoad : MTLLoadActionClear;
#endif
    g_currentEncoder = [[g_currentCmdBuffer
        renderCommandEncoderWithDescriptor:s_cachedRP] retain];
    g_currentPipeline = nil;
    MTLViewport vp;
    vp.originX = 0;
    vp.originY = 0;
#ifdef METALRENDER_HAS_METALFX
    vp.width = (double)s_cachedRP.colorAttachments[0].texture.width;
    vp.height = (double)s_cachedRP.colorAttachments[0].texture.height;
#else
    vp.width = (double)g_color.width;
    vp.height = (double)g_color.height;
#endif
    vp.znear = 0.0;
    vp.zfar = 1.0;
    [g_currentEncoder setViewport:vp];
    [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
    [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
    float camPos[4] = {(float)g_camX, (float)g_camY, (float)g_camZ,
                       g_skyBrightness};
    [g_currentEncoder setVertexBytes:camPos length:16 atIndex:3];
    float chunkOff[4] = {0, 0, 0, 0};
    [g_currentEncoder setVertexBytes:chunkOff length:16 atIndex:4];
    [g_currentEncoder setFrontFacingWinding:MTLWindingCounterClockwise];
    [g_currentEncoder setCullMode:MTLCullModeBack];
    if (g_blockAtlas) {
      [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
    }
    if (g_lightmap) {
      [g_currentEncoder setFragmentTexture:g_lightmap atIndex:1];
    }
    if (g_frameCount < 3) {
      dbg("Triple-buffer: renderSlot=%d reuse=%d lastCompleted=%d\n",
          g_renderSlot, reuseFrame ? 1 : 0,
          g_tbLastCompleted.load(std::memory_order_relaxed));
    }
  }
  return (jlong)0x1;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nEndFrame(
    JNIEnv *, jclass, jlong handle) {
  @autoreleasepool {
    (void)handle;
    uint64_t _prof_ef_t0 = mach_absolute_time();
    g_frameCount.fetch_add(1, std::memory_order_acq_rel);
    static uint64_t ft_last = 0;
    static uint64_t ft_acc = 0;
    static int ft_count = 0;
    uint64_t ft_now = mach_absolute_time();
    uint64_t ft_previous = ft_last;
    if (ft_previous > 0) {
      ft_acc += (ft_now - ft_previous);
      ft_count++;
    }
    ft_last = ft_now;

    if (ft_count > 0 && (g_frameCount % 600 == 0)) {
      ensureTimebase();
      double avg_ms = (double)(ft_acc / ft_count) * g_cachedTimebase.numer /
                      g_cachedTimebase.denom / 1000000.0;
      double avg_fps = (avg_ms > 0) ? (1000.0 / avg_ms) : 0;
      dbg("FRAME_TIMING: avg=%.2fms (%.1f FPS) over %d frames, draws=%d\n",
          avg_ms, avg_fps, ft_count, g_drawCallCount);
      ft_acc = 0;
      ft_count = 0;
    }

    {
      ensureTimebase();
      if (ft_previous > 0 && ft_now > ft_previous) {
        float frameMs =
            (float)((ft_now - ft_previous) * g_cachedTimebase.numer /
                    g_cachedTimebase.denom) /
                        1000000.0f;
        g_avgFrameTimeMs = g_avgFrameTimeMs * 0.95f + frameMs * 0.05f;
      }
    }
    if (g_frameCount <= 5 || g_frameCount % 500 == 0) {
      dbg("EndFrame #%d: draws=%d skips=%d encoder=%p pipeline=%p "
          "pipelineInhouse=%p texture=%dx%d proj[0]=%.3f mv[0]=%.3f "
          "cam=%.1f,%.1f,%.1f gpuDriven=%d meshShaders=%d\n",
          g_frameCount.load(std::memory_order_relaxed), g_drawCallCount,
          g_drawSkipCount, g_currentEncoder,
          g_currentPipeline, g_pipelineInhouse,
          g_color ? (int)g_color.width : 0, g_color ? (int)g_color.height : 0,
          g_projMatrix[0], g_mvMatrix[0], g_camX, g_camY, g_camZ,
          g_gpuDrivenEnabled ? 1 : 0, g_meshShadersActive ? 1 : 0);
    }

    if (g_frameCount % 300 == 0) {
      NSLog(@"[MetalRender] Frame %d — ActivePath: %@  ICB=%@  MeshShaders=%@  "
            @"OIT=%@  ArgBuf=%@  draws=%d",
            g_frameCount.load(std::memory_order_relaxed),
            g_meshShadersActive
                ? @"MESH_SHADER"
                : (g_gpuDrivenEnabled ? @"ICB_GPU_DRIVEN" : @"INHOUSE_VERTEX"),
            g_gpuDrivenEnabled ? @"ON" : @"OFF",
            g_meshShadersActive ? @"ON" : @"OFF",
            g_useProgrammableBlending ? @"ON" : @"OFF",
            g_useArgumentBuffers ? @"ON" : @"OFF", g_drawCallCount);
    }
    g_drawCallCount = 0;
    g_drawSkipCount = 0;
    if (g_currentEncoder) {
      [g_currentEncoder endEncoding];
      [g_currentEncoder release];
      g_currentEncoder = nil;
    }
    // Keep diagnostic/screenshot depth readback asynchronous with the frame.
    // A per-slot staging buffer avoids cross-frame writes. Dynamic-resolution
    // depth is not upscaled, so fail closed for that mode in nReadbackDepth.
    if (g_currentCmdBuffer && !g_wasLowResolutionFrame &&
        g_frameDepthTarget && g_tbDepthReadBuffer[g_renderSlot]) {
      id<MTLBlitCommandEncoder> depthBlit =
          [g_currentCmdBuffer blitCommandEncoder];
      if (depthBlit) {
        [depthBlit
            copyFromTexture:g_frameDepthTarget
                sourceSlice:0
                sourceLevel:0
               sourceOrigin:MTLOriginMake(0, 0, 0)
                 sourceSize:MTLSizeMake((NSUInteger)g_depthReadWidth,
                                        (NSUInteger)g_depthReadHeight, 1)
                   toBuffer:g_tbDepthReadBuffer[g_renderSlot]
          destinationOffset:0
     destinationBytesPerRow:g_depthReadBytesPerRow
   destinationBytesPerImage:g_depthReadBytesPerRow *
                            (NSUInteger)g_depthReadHeight];
        [depthBlit endEncoding];
      }
    }
    if (kHiZPathValidated && g_currentCmdBuffer &&
        g_hizDownsamplePipeline && g_hizPyramid &&
        !g_useMemorylessTargets) {
      id<MTLComputeCommandEncoder> hizEnc =
          [g_currentCmdBuffer computeCommandEncoder];
      if (hizEnc) {
        [hizEnc setComputePipelineState:g_hizDownsamplePipeline];
        id<MTLTexture> srcDepth = g_frameDepthTarget;
        if (srcDepth) {
          [hizEnc setTexture:srcDepth atIndex:0];
          [hizEnc setTexture:g_hizPyramid atIndex:1];
          hizUpdateThreadgroupSize(srcDepth);
          [hizEnc dispatchThreadgroups:g_hizGroups
                 threadsPerThreadgroup:g_hizThreads];
        }
        [hizEnc endEncoding];
      }
    }
#ifdef METALRENDER_HAS_METALFX

    if (@available(macOS 13.0, *)) {
      if (g_mfxScaler && g_wasLowResolutionFrame &&
          g_lrColor[g_renderSlot] &&
          g_mfxOutput[g_renderSlot] &&
          g_currentCmdBuffer) {
        g_mfxScaler.colorTexture = g_lrColor[g_renderSlot];
        g_mfxScaler.inputContentWidth = g_lrColor[g_renderSlot].width;
        g_mfxScaler.inputContentHeight = g_lrColor[g_renderSlot].height;
        g_mfxScaler.outputTexture = g_mfxOutput[g_renderSlot];
        [g_mfxScaler encodeToCommandBuffer:g_currentCmdBuffer];
        id<MTLBlitCommandEncoder> upscaleCopy =
            [g_currentCmdBuffer blitCommandEncoder];
        if (upscaleCopy) {
          [upscaleCopy
              copyFromTexture:g_mfxOutput[g_renderSlot]
                  sourceSlice:0
                  sourceLevel:0
                 sourceOrigin:MTLOriginMake(0, 0, 0)
                   sourceSize:MTLSizeMake(g_mfxOutput[g_renderSlot].width,
                                          g_mfxOutput[g_renderSlot].height, 1)
                    toTexture:g_color
             destinationSlice:0
             destinationLevel:0
            destinationOrigin:MTLOriginMake(0, 0, 0)];
          [upscaleCopy endEncoding];
        }
      }
    }
#endif
    if (g_currentCmdBuffer) {
      if (g_frameEvent) {
        g_eventCounter++;
        [g_currentCmdBuffer encodeSignalEvent:g_frameEvent
                                        value:g_eventCounter];
      }
      int completedSlot = g_renderSlot;

      bool wasReuseForThisFrame = g_wasReuseFrame;
      if (g_tbCmdBuf[completedSlot]) {
        [g_tbCmdBuf[completedSlot] release];
      }
      g_tbCmdBuf[completedSlot] = [g_currentCmdBuffer retain];
      {
        dispatch_semaphore_t capSema = g_frameSemaphore;
        if (capSema)
          dispatch_retain(capSema);
        uint64_t submissionSerial =
            g_submittedFrameSerial.fetch_add(1,
                                              std::memory_order_acq_rel) +
            1;
        [g_currentCmdBuffer addCompletedHandler:^(id<MTLCommandBuffer> cb) {
          mark_frame_submission_completed(submissionSerial);
          bool commandSucceeded =
              cb.status == MTLCommandBufferStatusCompleted;
          if (!commandSucceeded) {
            g_gpuCommandBufferErrorCount.fetch_add(
                1, std::memory_order_relaxed);
            NSError *err = cb.error;
            dbg("GPU_ERROR: slot=%d status=%ld err=%s code=%ld\n",
                completedSlot, (long)cb.status,
                err ? [[err localizedDescription] UTF8String] : "unknown",
                (long)(err ? err.code : -1));
            g_gpuNeedsRecovery.store(true, std::memory_order_release);
          }
          {
            std::lock_guard<std::mutex> lock(g_surfaceSlotMutex);
            if (commandSucceeded) {
              g_tbSlotState[completedSlot].store(
                  SurfaceSlotReadyForPresentation, std::memory_order_release);
              g_tbSlotReady[completedSlot].store(true,
                                                 std::memory_order_release);
              g_tbLastCompleted.store(completedSlot,
                                      std::memory_order_release);
              g_currentFrameReady.store(true, std::memory_order_release);
            } else {
              // Never publish a partial or undefined IOSurface. Invalidate any
              // unpresented older frame too, so Java cannot mistake a stale
              // surface for successful completion of this frame.
              for (int slot = 0; slot < kTripleBufferCount; slot++) {
                if (g_tbSlotState[slot].load(std::memory_order_acquire) ==
                    SurfaceSlotReadyForPresentation) {
                  g_tbSlotState[slot].store(SurfaceSlotAvailable,
                                            std::memory_order_release);
                  g_tbSlotReady[slot].store(true,
                                            std::memory_order_release);
                }
              }
              g_tbSlotState[completedSlot].store(
                  SurfaceSlotAvailable, std::memory_order_release);
              g_tbSlotReady[completedSlot].store(true,
                                                 std::memory_order_release);
              g_tbLastCompleted.store(-1, std::memory_order_release);
              g_currentFrameReady.store(false, std::memory_order_release);
            }
          }
          g_surfaceSlotChanged.notify_all();
          if (capSema && !wasReuseForThisFrame) {
            dispatch_semaphore_signal(capSema);
          }
          if (capSema)
            dispatch_release(capSema);
          if (@available(macOS 10.15, *)) {
            CFTimeInterval gpuStart = cb.GPUStartTime;
            CFTimeInterval gpuEnd = cb.GPUEndTime;
            if (gpuStart > 0 && gpuEnd > gpuStart) {
              double gpuMs = (gpuEnd - gpuStart) * 1000.0;
              g_lastGpuMs.store((float)gpuMs, std::memory_order_relaxed);
              uint64_t gpuUs = (uint64_t)(gpuMs * 1000.0);
              uint64_t reportAccumulatedUs = 0;
              uint32_t reportCompletedFrames = 0;
              {
                std::lock_guard<std::mutex> lock(g_gpuTelemetryMutex);
                g_gpuTelemetryAccumulatedUs += gpuUs;
                g_gpuTelemetryCompletedFrames++;
                if (g_gpuTelemetryCompletedFrames >= 120) {
                  reportAccumulatedUs = g_gpuTelemetryAccumulatedUs;
                  reportCompletedFrames = g_gpuTelemetryCompletedFrames;
                  g_gpuTelemetryAccumulatedUs = 0;
                  g_gpuTelemetryCompletedFrames = 0;
                }
              }
              if (reportCompletedFrames > 0) {
                double avg =
                    (double)reportAccumulatedUs / reportCompletedFrames;
                dbg("GPU_TIMING: avg=%.2fms (%.1f max-FPS) over %u frames\n",
                    avg / 1000.0, 1000000.0 / avg,
                    reportCompletedFrames);
              }
            }
          }
        }];
      }
      [g_currentCmdBuffer commit];

      if (!wasReuseForThisFrame) {
        int slotCount = std::max(
            2, std::min(kTripleBufferCount,
                        g_activeSurfaceSlots.load(std::memory_order_acquire)));
        g_currentBufferIndex = (g_currentBufferIndex + 1) % slotCount;
      }
      [g_currentCmdBuffer release];
      g_currentCmdBuffer = nil;
    }
    g_currentPipeline = nil;
    double now = CFAbsoluteTimeGetCurrent();
    if (now - g_lastThermalCheckTime > 1.0) {
      g_lastThermalCheckTime = now;
      NSProcessInfoThermalState state =
          [[NSProcessInfo processInfo] thermalState];
      g_thermalState = (int)state;

      if (state >= NSProcessInfoThermalStateCritical) {
        g_thermalQualityLevel = 2;
        g_dynamicLODScale = fmaxf(g_dynamicLODScale, 1.8f);
        if (g_frameCount % 60 == 0)
          dbg("THERMAL: Critical! quality=2, LOD scale=%.2f\n",
              g_dynamicLODScale);
      } else if (state >= NSProcessInfoThermalStateSerious) {
        g_thermalQualityLevel = 1;
        g_dynamicLODScale = fmaxf(g_dynamicLODScale, 1.4f);
        if (g_frameCount % 300 == 0)
          dbg("THERMAL: Serious. quality=1, LOD scale=%.2f\n",
              g_dynamicLODScale);
      } else {
        g_thermalQualityLevel = 0;
      }

      if (g_thermalQualityLevel == 0 && g_avgFrameTimeMs > 0.0f) {
        float frameHighWatermark = g_targetFrameTimeMs * 1.08f;
        float frameLowWatermark = g_targetFrameTimeMs * 0.84f;
        if (g_avgFrameTimeMs > frameHighWatermark) {

          g_dynamicLODScale = fminf(g_dynamicLODScale * 1.02f, 1.6f);
          if (g_frameCount % 300 == 0)
            dbg("ADAPTIVE_LOD: frame=%.1fms target=%.1fms, scale UP to %.2f\n",
                g_avgFrameTimeMs, g_targetFrameTimeMs, g_dynamicLODScale);
        } else if (g_avgFrameTimeMs < frameLowWatermark &&
                   g_dynamicLODScale > 1.0f) {

          g_dynamicLODScale = fmaxf(g_dynamicLODScale * 0.98f, 1.0f);
        }
      }
    }
    {
      std::lock_guard<std::mutex> lock(g_deferredMutex);

      int freed = 0;
      size_t i = 0;
      while (i < g_deferredDeletions.size()) {
        auto &dd = g_deferredDeletions[i];
        uint64_t completedSubmission =
            g_completedFrameSerial.load(std::memory_order_acquire);
        if (completedSubmission >= dd.retireAfterSubmission) {
          if (dd.isMega) {
            megaFree(dd.handle);
          } else {
            std::unique_lock<std::shared_mutex> bufLock(g_bufferMutex);
            auto bufIt = g_buffers.find(dd.handle);
            if (bufIt != g_buffers.end()) {
              [bufIt->second release];
              g_buffers.erase(bufIt);
              auto sizeIt = g_bufferSizes.find(dd.handle);
              if (sizeIt != g_bufferSizes.end()) {
                g_individualBufferBytes -=
                    std::min(g_individualBufferBytes, sizeIt->second);
                g_bufferSizes.erase(sizeIt);
              }
            }
          }

          dd = g_deferredDeletions.back();
          g_deferredDeletions.pop_back();
          freed++;
        } else {
          i++;
        }
      }
      if (freed > 0 && (g_frameCount % 300 == 0)) {
        dbg("DeferredDelete: freed %d buffers (%zu pending)\n", freed,
            g_deferredDeletions.size());
      }
    }
    g_prof_endFrame_acc += (mach_absolute_time() - _prof_ef_t0);
    g_prof_count++;

    if (g_prof_count > 0 && (g_frameCount % kProfileEmitInterval == 0)) {
      ensureTimebase();
      double ns_per_tick =
          (double)g_cachedTimebase.numer / g_cachedTimebase.denom;
      double drawAll_ms =
          (double)g_prof_drawAll_acc / g_prof_count * ns_per_tick / 1e6;
      double endFrame_ms =
          (double)g_prof_endFrame_acc / g_prof_count * ns_per_tick / 1e6;
      double waitRender_ms =
          (double)g_prof_waitRender_acc / g_prof_count * ns_per_tick / 1e6;
      double cglBind_ms =
          (double)g_prof_cglBind_acc / g_prof_count * ns_per_tick / 1e6;
      dbg("PROFILE: drawAll=%.2fms endFrame=%.2fms waitRender=%.2fms "
          "cglBind=%.2fms (avg over %d frames)\n",
          drawAll_ms, endFrame_ms, waitRender_ms, cglBind_ms, g_prof_count);
      NSLog(@"[MetalRender] PROFILE: drawAll=%.2fms endFrame=%.2fms "
            @"waitRender=%.2fms cglBind=%.2fms (avg/%d)",
            drawAll_ms, endFrame_ms, waitRender_ms, cglBind_ms, g_prof_count);
      g_prof_drawAll_acc = 0;
      g_prof_endFrame_acc = 0;
      g_prof_waitRender_acc = 0;
      g_prof_cglBind_acc = 0;
      g_prof_count = 0;
    }
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetProjectionMatrix(
    JNIEnv *env, jclass, jlong handle, jfloatArray matrix) {
  (void)handle;
  if (matrix && env->GetArrayLength(matrix) >= 16) {
    env->GetFloatArrayRegion(matrix, 0, 16, g_projMatrix);
    if (g_currentEncoder) {
      [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
    }
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetModelViewMatrix(
    JNIEnv *env, jclass, jlong handle, jfloatArray matrix) {
  (void)handle;
  if (matrix && env->GetArrayLength(matrix) >= 16) {
    env->GetFloatArrayRegion(matrix, 0, 16, g_mvMatrix);
    if (g_currentEncoder) {
      [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
    }
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetCameraPosition(
    JNIEnv *, jclass, jlong handle, jdouble x, jdouble y, jdouble z) {
  (void)handle;
  g_camX = x;
  g_camY = y;
  g_camZ = z;
  if (g_currentEncoder) {

    float camPos[4] = {(float)x, (float)y, (float)z, g_skyBrightness};
    [g_currentEncoder setVertexBytes:camPos length:16 atIndex:3];
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetFrameMatrices(
    JNIEnv *env, jclass, jlong handle, jfloatArray projMatrix,
    jfloatArray mvMatrix, jdouble camX, jdouble camY, jdouble camZ) {
  (void)handle;
  if (projMatrix && env->GetArrayLength(projMatrix) >= 16) {
    env->GetFloatArrayRegion(projMatrix, 0, 16, g_projMatrix);
  }
  if (mvMatrix && env->GetArrayLength(mvMatrix) >= 16) {
    env->GetFloatArrayRegion(mvMatrix, 0, 16, g_mvMatrix);
  }
  g_camX = camX;
  g_camY = camY;
  g_camZ = camZ;
  if (g_currentEncoder) {
    [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
    [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
    float camPos[4] = {(float)camX, (float)camY, (float)camZ, g_skyBrightness};
    [g_currentEncoder setVertexBytes:camPos length:16 atIndex:3];
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBindTexture(
    JNIEnv *, jclass, jlong handle, jlong textureHandle, jint slot) {
  (void)handle;
  if (textureHandle == 0)
    return;
  id<MTLTexture> tex =
      (__bridge id<MTLTexture>)(void *)(uintptr_t)textureHandle;
  if (slot == 0) {
    g_blockAtlas = tex;
  } else if (slot == 1) {
    g_lightmap = tex;
  }
  if (g_currentEncoder && tex) {
    [g_currentEncoder setFragmentTexture:tex atIndex:(NSUInteger)slot];
  }
}

static void astc_encode_block_4x4(const uint8_t *pixels, uint8_t out[16]) {
  memset(out, 0, 16);

  auto setBits = [&](int pos, int count, uint32_t val) {
    for (int i = 0; i < count; i++) {
      if (val & (1u << i)) {
        out[(pos + i) >> 3] |= (1 << ((pos + i) & 7));
      }
    }
  };

  setBits(0, 11, 0x14A);

  setBits(11, 2, 0);

  setBits(13, 4, 12);

  uint8_t mn[4] = {255, 255, 255, 255};
  uint8_t mx[4] = {0, 0, 0, 0};
  for (int i = 0; i < 16; i++) {
    for (int c = 0; c < 4; c++) {
      uint8_t v = pixels[i * 4 + c];
      if (v < mn[c])
        mn[c] = v;
      if (v > mx[c])
        mx[c] = v;
    }
  }

  uint8_t ep[8] = {mn[0], mx[0], mn[1], mx[1], mn[2], mx[2], mn[3], mx[3]};
  for (int i = 0; i < 8; i++) {
    setBits(17 + i * 8, 8, ep[i]);
  }

  int dr = mx[0] - mn[0], dg = mx[1] - mn[1];
  int db = mx[2] - mn[2], da = mx[3] - mn[3];
  int dot_max = dr * dr + dg * dg + db * db + da * da;
  for (int i = 0; i < 16; i++) {
    int w = 0;
    if (dot_max > 0) {
      int cr = pixels[i * 4] - mn[0];
      int cg = pixels[i * 4 + 1] - mn[1];
      int cb = pixels[i * 4 + 2] - mn[2];
      int ca = pixels[i * 4 + 3] - mn[3];
      int dot = cr * dr + cg * dg + cb * db + ca * da;
      w = (dot * 3 + dot_max / 2) / dot_max;
      if (w < 0)
        w = 0;
      if (w > 3)
        w = 3;
    }

    setBits(126 - i * 2, 2, (uint32_t)w);
  }
}

static uint8_t *compress_rgba_to_astc4x4(const uint8_t *rgba, int w, int h,
                                         size_t *outSize) {
  int bw = (w + 3) / 4;
  int bh = (h + 3) / 4;
  size_t compSize = (size_t)bw * bh * 16;
  uint8_t *comp = new uint8_t[compSize];
  for (int by = 0; by < bh; by++) {
    for (int bx = 0; bx < bw; bx++) {

      uint8_t block[64];
      for (int py = 0; py < 4; py++) {
        for (int px = 0; px < 4; px++) {
          int sx = bx * 4 + px;
          int sy = by * 4 + py;
          if (sx >= w)
            sx = w - 1;
          if (sy >= h)
            sy = h - 1;
          const uint8_t *src = rgba + ((size_t)sy * w + sx) * 4;
          uint8_t *dst = block + (py * 4 + px) * 4;
          dst[0] = src[0];
          dst[1] = src[1];
          dst[2] = src[2];
          dst[3] = src[3];
        }
      }
      astc_encode_block_4x4(block, comp + ((size_t)by * bw + bx) * 16);
    }
  }
  *outSize = compSize;
  return comp;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nCreateTexture2D(
    JNIEnv *env, jclass, jlong deviceHandle, jint width, jint height,
    jbyteArray pixelData) {
  static constexpr size_t kMaxTextureDimension2D = 16384;
  if (!env || !g_device || deviceHandle == 0 || !pixelData ||
      width <= 0 || height <= 0 ||
      (size_t)width > kMaxTextureDimension2D ||
      (size_t)height > kMaxTextureDimension2D)
    return 0;
  if ((uintptr_t)deviceHandle !=
      (uintptr_t)(__bridge void *)g_device)
    return 0;

  const size_t textureWidth = (size_t)width;
  const size_t textureHeight = (size_t)height;
  if (textureWidth > std::numeric_limits<size_t>::max() / 4)
    return 0;
  const size_t bytesPerRow = textureWidth * 4;
  if (textureHeight >
      std::numeric_limits<size_t>::max() / bytesPerRow)
    return 0;
  const size_t requiredBytes = bytesPerRow * textureHeight;
  if (requiredBytes >
          (size_t)std::numeric_limits<jsize>::max() ||
      (size_t)env->GetArrayLength(pixelData) < requiredBytes)
    return 0;

  jbyte *data = env->GetByteArrayElements(pixelData, NULL);
  if (!data)
    return 0;

  id<MTLTexture> tex = nil;

  bool useASTC = false;
  if (useASTC) {
    MTLTextureDescriptor *desc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatASTC_4x4_LDR
                                     width:(NSUInteger)width
                                    height:(NSUInteger)height
                                 mipmapped:NO];
    desc.usage = MTLTextureUsageShaderRead;
    desc.storageMode = MTLStorageModeShared;
    tex = [g_device newTextureWithDescriptor:desc];
    if (tex) {
      size_t compSize = 0;
      uint8_t *comp = compress_rgba_to_astc4x4((const uint8_t *)data, width,
                                               height, &compSize);
      MTLRegion region =
          MTLRegionMake2D(0, 0, (NSUInteger)width, (NSUInteger)height);

      [tex replaceRegion:region
             mipmapLevel:0
               withBytes:comp
             bytesPerRow:(NSUInteger)((width / 4) * 16)];
      delete[] comp;
      dbg("nCreateTexture2D: created %dx%d ASTC 4x4 texture %p (%.1fKB vs "
          "%.1fKB RGBA)\n",
          width, height, tex, compSize / 1024.0, (width * height * 4) / 1024.0);
    }
  }

  if (!tex) {
    MTLTextureDescriptor *desc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                     width:(NSUInteger)width
                                    height:(NSUInteger)height
                                 mipmapped:NO];
    desc.usage = MTLTextureUsageShaderRead;
    desc.storageMode = MTLStorageModeShared;
    tex = [g_device newTextureWithDescriptor:desc];
    if (tex) {
      MTLRegion region =
          MTLRegionMake2D(0, 0, (NSUInteger)width, (NSUInteger)height);
      [tex replaceRegion:region
             mipmapLevel:0
               withBytes:data
             bytesPerRow:(NSUInteger)bytesPerRow];
      dbg("nCreateTexture2D: created %dx%d RGBA texture %p\n", width, height,
          tex);
    } else {
      dbg("nCreateTexture2D: failed to create %dx%d texture\n", width, height);
    }
  }
  env->ReleaseByteArrayElements(pixelData, data, JNI_ABORT);
  // newTextureWithDescriptor already returns a +1 object under manual retain
  // counting; the Java handle owns that reference until nDestroyTexture2D.
  return tex ? (jlong)(uintptr_t)(__bridge void *)tex : 0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDestroyTexture2D(
    JNIEnv *, jclass, jlong textureHandle) {
  if (!textureHandle)
    return;
  id<MTLTexture> tex =
      (__bridge id<MTLTexture>)(void *)(uintptr_t)textureHandle;
  if (tex == g_blockAtlas)
    g_blockAtlas = nil;
  if (tex == g_lightmap)
    g_lightmap = nil;
  if (tex == g_entityTexture)
    g_entityTexture = nil;
  if (tex)
    [tex release];
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUpdateTexture2D(
    JNIEnv *env, jclass, jlong textureHandle, jint width, jint height,
    jbyteArray pixelData) {
  if (!textureHandle || !pixelData || width <= 0 || height <= 0)
    return JNI_FALSE;
  ensure_device();
  id<MTLTexture> tex =
      (__bridge id<MTLTexture>)(void *)(uintptr_t)textureHandle;
  if (!tex || !g_device || !g_queue ||
      tex.pixelFormat != MTLPixelFormatRGBA8Unorm ||
      (NSUInteger)width > tex.width || (NSUInteger)height > tex.height)
    return JNI_FALSE;
  size_t tightBytesPerRow = (size_t)width * 4;
  if (tightBytesPerRow / 4 != (size_t)width)
    return JNI_FALSE;
  size_t requiredBytes = tightBytesPerRow * (size_t)height;
  if ((size_t)height != 0 &&
      requiredBytes / (size_t)height != tightBytesPerRow)
    return JNI_FALSE;
  if ((size_t)env->GetArrayLength(pixelData) < requiredBytes)
    return JNI_FALSE;

  jbyte *data = env->GetByteArrayElements(pixelData, NULL);
  if (!data)
    return JNI_FALSE;
  bool uploadSubmitted = false;

  // CPU-side replaceRegion on a shared texture races older in-flight frames
  // that still sample that texture. Stage the upload and commit a blit on the
  // same queue instead: previous draw command buffers finish before the blit,
  // and the next frame is committed after it.
  size_t stagedBytesPerRow = (tightBytesPerRow + 255u) & ~255u;
  size_t stagedLength = stagedBytesPerRow * (size_t)height;
  id<MTLBuffer> staging =
      [g_device newBufferWithLength:stagedLength
                            options:(MTLResourceStorageModeShared |
                                     MTLResourceCPUCacheModeWriteCombined)];
  if (staging) {
    uint8_t *destination = (uint8_t *)[staging contents];
    const uint8_t *source = (const uint8_t *)data;
    for (int row = 0; row < height; row++) {
      memcpy(destination + (size_t)row * stagedBytesPerRow,
             source + (size_t)row * tightBytesPerRow,
             tightBytesPerRow);
    }

    @autoreleasepool {
      id<MTLCommandBuffer> uploadCommandBuffer = [g_queue commandBuffer];
      id<MTLBlitCommandEncoder> blit =
          uploadCommandBuffer ? [uploadCommandBuffer blitCommandEncoder] : nil;
      if (blit) {
        [blit copyFromBuffer:staging
                sourceOffset:0
           sourceBytesPerRow:stagedBytesPerRow
         sourceBytesPerImage:stagedLength
                  sourceSize:MTLSizeMake((NSUInteger)width,
                                         (NSUInteger)height, 1)
                   toTexture:tex
            destinationSlice:0
            destinationLevel:0
                   destinationOrigin:MTLOriginMake(0, 0, 0)];
        [blit endEncoding];
        // Every upload needs its own completion check. Waiting only for the
        // newest same-queue upload orders earlier work, but its final status
        // cannot reveal an error from an earlier command buffer.
        [uploadCommandBuffer addCompletedHandler:^(id<MTLCommandBuffer> cb) {
          if (cb.status != MTLCommandBufferStatusCompleted) {
            g_gpuCommandBufferErrorCount.fetch_add(
                1, std::memory_order_relaxed);
            g_gpuNeedsRecovery.store(true, std::memory_order_release);
            NSError *error = cb.error;
            dbg("WARN: staged texture upload failed: status=%ld error=%s\n",
                (long)cb.status,
                error ? [[error localizedDescription] UTF8String]
                      : "unknown");
          }
        }];
        [uploadCommandBuffer commit];
        {
          std::lock_guard<std::mutex> lock(g_textureUploadMutex);
          if (g_lastTextureUploadCommandBuffer)
            [g_lastTextureUploadCommandBuffer release];
          g_lastTextureUploadCommandBuffer =
              [uploadCommandBuffer retain];
        }
        uploadSubmitted = true;
      } else {
        dbg("WARN: Could not create staged texture upload encoder\n");
      }
    }
    // Command buffers retain encoded resources by default.
    [staging release];
  } else {
    dbg("WARN: Could not allocate %zu-byte staged texture upload\n",
        stagedLength);
  }
  env->ReleaseByteArrayElements(pixelData, data, JNI_ABORT);
  return uploadSubmitted ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetDeviceHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_device;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetShaderLibraryHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;

  if (!g_shaderLibrary)
    return 0;
  return (jlong)(uintptr_t)(__bridge void *)g_shaderLibrary;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetInhousePipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineOpaque;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetDefaultPipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineOpaque;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetGLTextureId(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return 0;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIOSurfaceWidth(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return g_rtWidth;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIOSurfaceHeight(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return g_rtHeight;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsFrameReady(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;

  int last = g_tbLastCompleted.load(std::memory_order_acquire);
  if (last >= 0 &&
      g_tbSlotState[last].load(std::memory_order_acquire) ==
          SurfaceSlotReadyForPresentation)
    return JNI_TRUE;
  return JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nWaitForRender(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  uint64_t _prof_wr_t0 = mach_absolute_time();
  std::unique_lock<std::mutex> lock(g_surfaceSlotMutex);
  auto frameReady = [] {
    int last = g_tbLastCompleted.load(std::memory_order_acquire);
    return g_shuttingDown.load(std::memory_order_acquire) ||
           (last >= 0 &&
            g_tbSlotState[last].load(std::memory_order_acquire) ==
                SurfaceSlotReadyForPresentation);
  };
  if (!frameReady()) {
    int waitMs = std::max(8, (int)ceilf(g_targetFrameTimeMs * 4.0f));
    g_surfaceSlotChanged.wait_for(lock, std::chrono::milliseconds(waitMs),
                                 frameReady);
  }
  g_prof_waitRender_acc += (mach_absolute_time() - _prof_wr_t0);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nRecycleUnpresentedFrames(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  bool recycled = false;
  {
    std::lock_guard<std::mutex> lock(g_surfaceSlotMutex);
    for (int slot = 0; slot < kTripleBufferCount; slot++) {
      if (g_tbSlotState[slot].load(std::memory_order_acquire) ==
          SurfaceSlotReadyForPresentation) {
        g_tbSlotState[slot].store(SurfaceSlotAvailable,
                                  std::memory_order_release);
        recycled = true;
      }
    }
    if (recycled) {
      g_tbLastCompleted.store(-1, std::memory_order_release);
      g_currentFrameReady.store(false, std::memory_order_release);
    }
  }
  if (recycled)
    g_surfaceSlotChanged.notify_all();
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nReleaseBoundPresentationSurface(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  {
    std::lock_guard<std::mutex> lock(g_surfaceSlotMutex);
    int boundSlot = g_glBoundSlot.exchange(-1, std::memory_order_acq_rel);
    if (boundSlot >= 0 && boundSlot < kTripleBufferCount &&
        g_tbSlotState[boundSlot].load(std::memory_order_acquire) ==
            SurfaceSlotBoundToGL) {
      // The Java caller finishes GL and deletes/detaches its rectangle texture
      // before this hand-off. Releasing only the slot explicitly owned by GL
      // preserves the normal ReadyForPresentation recycling rules.
      g_tbSlotState[boundSlot].store(SurfaceSlotAvailable,
                                     std::memory_order_release);
    }

    int lastCompleted =
        g_tbLastCompleted.load(std::memory_order_acquire);
    if (lastCompleted == boundSlot || lastCompleted < 0 ||
        lastCompleted >= kTripleBufferCount ||
        g_tbSlotState[lastCompleted].load(std::memory_order_acquire) !=
            SurfaceSlotReadyForPresentation) {
      int replacementCompleted = -1;
      for (int slot = 0; slot < kTripleBufferCount; slot++) {
        if (g_tbSlotState[slot].load(std::memory_order_acquire) ==
            SurfaceSlotReadyForPresentation) {
          replacementCompleted = slot;
        }
      }
      g_tbLastCompleted.store(replacementCompleted,
                              std::memory_order_release);
      g_currentFrameReady.store(replacementCompleted >= 0,
                                std::memory_order_release);
    }
  }
  g_surfaceSlotChanged.notify_all();
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBindIOSurfaceToTexture(
    JNIEnv *, jclass, jlong handle, jint glTexture) {
  (void)handle;
  uint64_t _prof_cgl_t0 = mach_absolute_time();
  CGLContextObj cglCtx = CGLGetCurrentContext();
  if (!cglCtx || glTexture == 0 || g_rtWidth <= 0 || g_rtHeight <= 0)
    return JNI_FALSE;

  // CGL has no cross-API fence that Metal can wait on here. Finish the previous
  // GL use before detaching its IOSurface; this is the conservative ownership
  // hand-off and prevents Metal from overwriting a surface still being sampled.
  glFinish();

  std::lock_guard<std::mutex> lock(g_surfaceSlotMutex);
  int previousBound = g_glBoundSlot.exchange(-1, std::memory_order_acq_rel);
  if (previousBound >= 0) {
    g_tbSlotState[previousBound].store(SurfaceSlotAvailable,
                                       std::memory_order_release);
  }

  int blitSlot = g_tbLastCompleted.load(std::memory_order_acquire);
  if (blitSlot < 0 ||
      g_tbSlotState[blitSlot].load(std::memory_order_acquire) !=
          SurfaceSlotReadyForPresentation) {
    for (int i = 0; i < kTripleBufferCount; i++) {
      if (g_tbSlotState[i].load(std::memory_order_acquire) ==
          SurfaceSlotReadyForPresentation) {
        blitSlot = i;
        break;
      }
    }
  }
  if (blitSlot < 0 ||
      g_tbSlotState[blitSlot].load(std::memory_order_acquire) !=
          SurfaceSlotReadyForPresentation ||
      !g_tbIOSurface[blitSlot]) {
    g_surfaceSlotChanged.notify_all();
    return JNI_FALSE;
  }

  IOSurfaceRef blitSurface = g_tbIOSurface[blitSlot];
  int w = std::max(1, g_rtWidth);
  int h = std::max(1, g_rtHeight);
  glBindTexture(GL_TEXTURE_RECTANGLE, (GLuint)glTexture);
  CGLError err = CGLTexImageIOSurface2D(
      cglCtx, GL_TEXTURE_RECTANGLE, GL_RGBA, (GLsizei)w, (GLsizei)h,
      GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, blitSurface, 0);
  glBindTexture(GL_TEXTURE_RECTANGLE, 0);
  if (err == kCGLNoError) {
    g_tbSlotState[blitSlot].store(SurfaceSlotBoundToGL,
                                  std::memory_order_release);
    g_glBoundSlot.store(blitSlot, std::memory_order_release);
    // Completed frames older than the one selected above will never be
    // presented. Recycle them now rather than exhausting the ring.
    for (int i = 0; i < kTripleBufferCount; i++) {
      if (i != blitSlot &&
          g_tbSlotState[i].load(std::memory_order_acquire) ==
              SurfaceSlotReadyForPresentation) {
        g_tbSlotState[i].store(SurfaceSlotAvailable,
                               std::memory_order_release);
      }
    }
  }
  g_surfaceSlotChanged.notify_all();
  g_prof_cglBind_acc += (mach_absolute_time() - _prof_cgl_t0);
  return (err == kCGLNoError) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nReadbackPixels(
    JNIEnv *env, jclass, jlong handle, jobject dest) {
  (void)handle;
  if (!dest || !env)
    return JNI_FALSE;
  void *destPtr = env->GetDirectBufferAddress(dest);
  if (!destPtr)
    return JNI_FALSE;
  {
    std::lock_guard<std::mutex> lock(g_surfaceSlotMutex);
    int readbackSlot = g_tbLastCompleted.load(std::memory_order_acquire);
    if (readbackSlot < 0 ||
        g_tbSlotState[readbackSlot].load(std::memory_order_acquire) !=
            SurfaceSlotReadyForPresentation) {
      readbackSlot = -1;
      for (int slot = 0; slot < kTripleBufferCount; slot++) {
        if (g_tbSlotState[slot].load(std::memory_order_acquire) ==
            SurfaceSlotReadyForPresentation) {
          readbackSlot = slot;
          break;
        }
      }
    }
    if (readbackSlot < 0 || !g_tbColor[readbackSlot] ||
        g_tbSlotState[readbackSlot].load(std::memory_order_acquire) !=
            SurfaceSlotReadyForPresentation) {
      return JNI_FALSE;
    }

    id<MTLTexture> readbackTexture = g_tbColor[readbackSlot];
    int w = (int)readbackTexture.width;
    int h = (int)readbackTexture.height;
    jlong capacity = env->GetDirectBufferCapacity(dest);
    if (w <= 0 || h <= 0 || capacity < (jlong)(w * h * 4))
      return JNI_FALSE;

    // ReadyForPresentation is published only by the command-buffer completion
    // handler. Holding the slot lock keeps this exact completed texture from
    // being recycled while the conservative CPU fallback copies it.
    [readbackTexture getBytes:destPtr
                  bytesPerRow:(NSUInteger)(w * 4)
                   fromRegion:MTLRegionMake2D(0, 0, w, h)
                  mipmapLevel:0];
    g_tbSlotState[readbackSlot].store(SurfaceSlotAvailable,
                                      std::memory_order_release);

    int replacementCompleted = -1;
    for (int slot = 0; slot < kTripleBufferCount; slot++) {
      if (g_tbSlotState[slot].load(std::memory_order_acquire) ==
          SurfaceSlotReadyForPresentation) {
        replacementCompleted = slot;
      }
    }
    g_tbLastCompleted.store(replacementCompleted, std::memory_order_release);
    g_currentFrameReady.store(replacementCompleted >= 0,
                              std::memory_order_release);
  }
  g_surfaceSlotChanged.notify_all();
  return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nReadbackDepth(
    JNIEnv *env, jclass, jlong handle, jobject dest) {
  (void)handle;
  if (!dest || !env || g_wasLowResolutionFrame)
    return JNI_FALSE;
  int slot = g_tbLastCompleted.load(std::memory_order_acquire);
  if (slot < 0 || !g_tbDepthReadBuffer[slot] ||
      !g_tbSlotReady[slot].load(std::memory_order_acquire))
    return JNI_FALSE;
  void *destPtr = env->GetDirectBufferAddress(dest);
  if (!destPtr)
    return JNI_FALSE;
  jlong capacity = env->GetDirectBufferCapacity(dest);
  NSUInteger tightRow = (NSUInteger)g_depthReadWidth * sizeof(float);
  NSUInteger tightSize = tightRow * (NSUInteger)g_depthReadHeight;
  if (capacity < (jlong)tightSize)
    return JNI_FALSE;
  const uint8_t *source =
      (const uint8_t *)g_tbDepthReadBuffer[slot].contents;
  uint8_t *destination = (uint8_t *)destPtr;
  if (g_depthReadBytesPerRow == tightRow) {
    memcpy(destination, source, tightSize);
  } else {
    for (int row = 0; row < g_depthReadHeight; row++) {
      memcpy(destination + (NSUInteger)row * tightRow,
             source + (NSUInteger)row * g_depthReadBytesPerRow, tightRow);
    }
  }
  return JNI_TRUE;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetEntityPipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineEntity;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetEntityTranslucentPipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineEntityTranslucent;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetEntityEmissivePipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineEntityEmissive;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetParticlePipelineHandle(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  return (jlong)(uintptr_t)(__bridge void *)g_pipelineParticle;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetEntityOverlay(
    JNIEnv *, jclass, jlong frameContext, jfloat hurtTime, jfloat whiteFlash,
    jfloat alpha) {
  (void)frameContext;
  (void)alpha;
  g_entityOverlayParams[0] = hurtTime;
  g_entityOverlayParams[1] = whiteFlash;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetWaterFog(
    JNIEnv *, jclass, jlong frameContext, jfloat waterFog) {
  (void)frameContext;
  g_entityOverlayParams[2] = waterFog;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetSkyBrightness(
    JNIEnv *, jclass, jlong frameContext, jfloat brightness) {
  (void)frameContext;
  g_skyBrightness = brightness;

  g_entityOverlayParams[3] = brightness;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetEntityTintColor(
    JNIEnv *, jclass, jlong frameContext, jfloat r, jfloat g, jfloat b,
    jfloat a) {
  (void)frameContext;
  g_entityTintColor[0] = r;
  g_entityTintColor[1] = g;
  g_entityTintColor[2] = b;
  g_entityTintColor[3] = a;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nBindEntityTexture(
    JNIEnv *, jclass, jlong frameContext, jlong textureHandle) {
  (void)frameContext;
  if (textureHandle == 0) {
    g_entityTexture = nil;
    if (g_frameCount < 5) {
      dbg("nBindEntityTexture: clearing entity texture\n");
    }
    return;
  }
  g_entityTexture = (__bridge id<MTLTexture>)(void *)(uintptr_t)textureHandle;
  if (g_frameCount < 5) {
    dbg("nBindEntityTexture: set g_entityTexture=%p (handle=%lld)\n",
        g_entityTexture, (long long)textureHandle);
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawEntityBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer, jint vertexCount,
    jint baseVertex, jint renderFlags) {
  (void)frameContext;
  if (!g_currentEncoder || vertexCount <= 0)
    return;
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  if (!vbRes.buf)
    return;
  id<MTLBuffer> vb = vbRes.buf;
  if (!(renderFlags & 0x8)) {
    id<MTLRenderPipelineState> pipeline = g_pipelineEntity;
    id<MTLDepthStencilState> depthSt =
        g_depthState ? g_depthState : g_depthStateNoWrite;
    if (renderFlags & 0x2) {
      pipeline = g_pipelineEntityEmissive ? g_pipelineEntityEmissive
                                          : g_pipelineEntity;
    } else if (renderFlags & 0x1) {
      pipeline = g_pipelineEntityTranslucent ? g_pipelineEntityTranslucent
                                             : g_pipelineEntity;
      depthSt = g_depthStateNoWrite ? g_depthStateNoWrite : g_depthState;
    }
    if (!pipeline) {
      pipeline = g_pipelineInhouse;
      if (!pipeline)
        return;
    }
    [g_currentEncoder setRenderPipelineState:pipeline];
    [g_currentEncoder setDepthStencilState:depthSt];
  }
  [g_currentEncoder setVertexBuffer:vb
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                              length:sizeof(g_entityOverlayParams)
                             atIndex:5];
  if (g_entityTexture) {
    [g_currentEncoder setFragmentTexture:g_entityTexture atIndex:0];
  } else {
    if (g_blockAtlas) {
      [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
    }
  }
  if (g_lightmap) {
    [g_currentEncoder setFragmentTexture:g_lightmap atIndex:1];
  }

  [g_currentEncoder setCullMode:MTLCullModeNone];
  [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                       vertexStart:(NSUInteger)baseVertex
                       vertexCount:(NSUInteger)vertexCount];
  g_drawCallCount++;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawEntityBufferIndexed(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer, jlong indexBuffer,
    jint indexCount, jint baseIndex, jint renderFlags) {
  (void)frameContext;
  if (!g_currentEncoder || indexCount <= 0)
    return;
  ResolvedBuf vbRes = resolve_buffer((uint64_t)vertexBuffer);
  ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
  if (!vbRes.buf || !ibRes.buf)
    return;
  id<MTLBuffer> vb = vbRes.buf;
  id<MTLBuffer> ib = ibRes.buf;
  id<MTLRenderPipelineState> pipeline = g_pipelineEntity;
  id<MTLDepthStencilState> depthSt =
      g_depthState ? g_depthState : g_depthStateNoWrite;
  if (renderFlags & 0x2) {
    pipeline =
        g_pipelineEntityEmissive ? g_pipelineEntityEmissive : g_pipelineEntity;
  } else if (renderFlags & 0x1) {
    pipeline = g_pipelineEntityTranslucent ? g_pipelineEntityTranslucent
                                           : g_pipelineEntity;
    depthSt = g_depthStateNoWrite ? g_depthStateNoWrite : g_depthState;
  }
  if (!pipeline) {
    pipeline = g_pipelineInhouse;
    if (!pipeline)
      return;
  }
  [g_currentEncoder setRenderPipelineState:pipeline];
  [g_currentEncoder setDepthStencilState:depthSt];
  [g_currentEncoder setVertexBuffer:vb
                             offset:(NSUInteger)vbRes.offset
                            atIndex:0];
  [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                              length:sizeof(g_entityOverlayParams)
                             atIndex:5];
  if (g_entityTexture) {
    [g_currentEncoder setFragmentTexture:g_entityTexture atIndex:0];
  } else if (g_blockAtlas) {
    [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
  }
  if (g_lightmap) {
    [g_currentEncoder setFragmentTexture:g_lightmap atIndex:1];
  }

  [g_currentEncoder setCullMode:MTLCullModeNone];
  [g_currentEncoder
      drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                 indexCount:(NSUInteger)indexCount
                  indexType:MTLIndexTypeUInt32
                indexBuffer:ib
          indexBufferOffset:(NSUInteger)(ibRes.offset +
                                         (size_t)baseIndex * sizeof(uint32_t))];
  g_drawCallCount++;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadCameraUniforms(
    JNIEnv *env, jclass, jlong handle, jfloatArray viewProj, jfloatArray proj,
    jfloatArray modelView, jfloatArray cameraPos, jfloatArray frustumPlanes,
    jfloat screenW, jfloat screenH, jfloat nearPlane, jfloat farPlane,
    jint totalChunks) {
  (void)handle;
  int bufIdx = g_currentBufferIndex % kTripleBufferCount;
  id<MTLBuffer> buf = g_tripleBuffers[bufIdx];
  if (!buf)
    return;
  CameraUniformsCPU *u = (CameraUniformsCPU *)[buf contents];
  if (viewProj && env->GetArrayLength(viewProj) >= 16)
    env->GetFloatArrayRegion(viewProj, 0, 16, u->viewProjection);
  if (proj && env->GetArrayLength(proj) >= 16)
    env->GetFloatArrayRegion(proj, 0, 16, u->projection);
  if (modelView && env->GetArrayLength(modelView) >= 16)
    env->GetFloatArrayRegion(modelView, 0, 16, u->modelView);
  if (cameraPos && env->GetArrayLength(cameraPos) >= 4)
    env->GetFloatArrayRegion(cameraPos, 0, 4, u->cameraPosition);
  if (frustumPlanes && env->GetArrayLength(frustumPlanes) >= 24)
    env->GetFloatArrayRegion(frustumPlanes, 0, 24, u->frustumPlanes);
  u->screenSize[0] = screenW;
  u->screenSize[1] = screenH;
  u->nearPlane = nearPlane;
  u->farPlane = farPlane;
  u->frameIndex = (uint32_t)g_frameCount;

  u->hizMipCount = g_useMemorylessTargets ? 0 : g_hizMipCount;
  u->totalChunks = (uint32_t)totalChunks;
  u->waterFog = g_entityOverlayParams[2];
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadSubChunkData(
    JNIEnv *env, jclass, jlong handle, jobject directBuffer, jint count) {
  (void)handle;
  if (!g_device || !directBuffer || count <= 0)
    return;
  void *ptr = env->GetDirectBufferAddress(directBuffer);
  jlong cap = env->GetDirectBufferCapacity(directBuffer);
  if (!ptr)
    return;
  size_t entrySize = 48;
  size_t totalSize = (size_t)count * entrySize;
  if ((size_t)cap < totalSize)
    return;
  if (!g_subChunkBuffer || g_subChunkBuffer.length < totalSize) {
    if (g_subChunkBuffer)
      [g_subChunkBuffer release];
    g_subChunkBuffer = [g_device newBufferWithLength:totalSize
                                             options:MTLStorageModeShared];
  }
  memcpy([g_subChunkBuffer contents], ptr, totalSize);
  g_gpuSubChunkCount = (uint32_t)count;
  size_t argsSize = (size_t)count * sizeof(uint32_t) * 4;
  if (!g_cullDrawArgsBuffer || g_cullDrawArgsBuffer.length < argsSize) {
    if (g_cullDrawArgsBuffer)
      [g_cullDrawArgsBuffer release];
    g_cullDrawArgsBuffer = [g_device newBufferWithLength:argsSize
                                                 options:MTLStorageModeShared];
  }
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetGPUDrivenEnabled(
    JNIEnv *, jclass, jlong handle, jboolean enabled) {
  (void)handle;
  MetalFeatureCaps caps = current_feature_caps();
  if (caps.indirectCommandBuffers && caps.argumentBuffers)
    g_icbCapable = true;
  g_gpuDrivenEnabled = g_icbCapable && (enabled != JNI_FALSE);
  dbg("GPU-driven rendering: %s (icbCapable=%d)\n",
      g_gpuDrivenEnabled ? "enabled" : "disabled", g_icbCapable ? 1 : 0);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nRunGPUCulling(
    JNIEnv *, jclass, jlong handle, jint chunkCount) {
  (void)handle;
  if (!g_device || chunkCount <= 0)
    return 0;
  if (!g_visibleIndicesBuffer || !g_cullDrawCountBuffer) {
    dbg("GPU Cull: buffers not allocated\n");
    return 0;
  }
  uint32_t count = (uint32_t)chunkCount;
  size_t neededSize = (size_t)count * sizeof(uint32_t);
  if (g_visibleIndicesBuffer.length < neededSize) {
    if (g_visibleIndicesBuffer)
      [g_visibleIndicesBuffer release];
    g_visibleIndicesBuffer =
        [g_device newBufferWithLength:neededSize options:MTLStorageModeShared];
  }
  // The previous implementation committed a separate command buffer and
  // immediately waited for it, stalling every frame. Until culling is encoded
  // into the main frame command stream with ping-ponged results, use the safe
  // CPU passthrough instead.
  uint32_t *indices = (uint32_t *)[g_visibleIndicesBuffer contents];
  for (uint32_t i = 0; i < count; i++) {
    indices[i] = i;
  }
  *(uint32_t *)[g_cullDrawCountBuffer contents] = count;
  if (g_frameCount < 5 || (g_frameCount % 300 == 0)) {
    dbg("GPU Cull [cpu-passthrough]: all %u chunks marked visible\n", count);
  }
  return (jint)count;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetGPUVisibleCount(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  if (!g_cullDrawCountBuffer)
    return 0;
  uint32_t *count = (uint32_t *)[g_cullDrawCountBuffer contents];
  return (jint)(*count);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nExecuteIndirectDraws(
    JNIEnv *, jclass, jlong frameContext, jlong vertexBuffer,
    jlong indexBuffer) {
  (void)frameContext;
  (void)vertexBuffer;
  if (!g_currentEncoder || !g_visibleIndicesBuffer || !g_cullDrawCountBuffer ||
      !g_subChunkBuffer)
    return;
  uint32_t visibleCount = *(uint32_t *)[g_cullDrawCountBuffer contents];
  if (visibleCount == 0)
    return;
  visibleCount = std::min(visibleCount, g_maxGPUDrawCalls);
  if (!g_currentPipeline && g_pipelineInhouse) {
    [g_currentEncoder setRenderPipelineState:g_pipelineInhouse];
    g_currentPipeline = g_pipelineInhouse;
    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
  }
  if (!g_currentPipeline)
    return;
  struct SubChunkCPU {
    float aabbMin[4];
    float aabbMax[4];
    uint32_t bufHandleHi;
    uint32_t bufHandleLo;
    uint32_t indexCount;
    uint32_t flags;
  };
  const uint32_t *visibleIndices =
      (const uint32_t *)[g_visibleIndicesBuffer contents];
  const SubChunkCPU *chunks = (const SubChunkCPU *)[g_subChunkBuffer contents];
  const float *chunkUniforms =
      g_chunkUniformsBuffer ? (const float *)[g_chunkUniformsBuffer contents]
                            : nullptr;
  ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBuffer);
  id<MTLBuffer> lastVB = nil;
  size_t lastVBOffset = 0;
  for (uint32_t i = 0; i < visibleCount; i++) {
    uint32_t chunkIdx = visibleIndices[i];
    if (chunkIdx >= g_gpuSubChunkCount)
      continue;
    const SubChunkCPU &entry = chunks[chunkIdx];
    uint64_t bufHandle =
        ((uint64_t)entry.bufHandleHi << 32) | (uint64_t)entry.bufHandleLo;
    uint32_t idxCount = entry.indexCount;
    if (idxCount == 0)
      continue;
    ResolvedBuf vbRes = resolve_buffer(bufHandle);
    if (!vbRes.buf)
      continue;
    if (chunkUniforms) {
      [g_currentEncoder setVertexBytes:&chunkUniforms[chunkIdx * 4]
                                length:16
                               atIndex:4];
    }
    if (vbRes.buf != lastVB || vbRes.offset != lastVBOffset) {
      [g_currentEncoder setVertexBuffer:vbRes.buf
                                 offset:(NSUInteger)vbRes.offset
                                atIndex:0];
      lastVB = vbRes.buf;
      lastVBOffset = vbRes.offset;
    }
    if (ibRes.buf) {
      [g_currentEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                   indexCount:(NSUInteger)idxCount
                                    indexType:MTLIndexTypeUInt32
                                  indexBuffer:ibRes.buf
                            indexBufferOffset:(NSUInteger)ibRes.offset];
    } else {
      [g_currentEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                           vertexStart:0
                           vertexCount:(NSUInteger)idxCount];
    }
    g_drawCallCount++;
  }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetThermalState(
    JNIEnv *, jclass) {
  return (jint)g_thermalState;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetTemporalScale(
    JNIEnv *, jclass, jfloat scale) {
  float clamped = std::max(0.2f, std::min(1.0f, (float)scale));
  if (fabsf(clamped - g_scale) < 0.001f)
    return;
  g_scale = clamped;
  g_targetScale = clamped;
  g_allocatedRenderWidth = 0;
  g_allocatedRenderHeight = 0;
  if (!g_currentEncoder)
    ensure_offscreen();
  dbg("Dynamic resolution scale set to %.3f\n", clamped);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetRenderDistance(
    JNIEnv *, jclass, jint distanceBlocks) {

  if (distanceBlocks > 0)
    g_configuredRenderDistBlocks = (int)distanceBlocks;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nSetFeatureFlags(
    JNIEnv *, jclass, jboolean icb, jboolean meshShaders, jboolean argBuffers,
    jboolean progBlend) {
  MetalFeatureCaps caps = current_feature_caps();
  bool prevMemoryless = g_useMemorylessTargets;
  bool requestedArgumentBuffers =
      ((argBuffers == JNI_TRUE) || (icb == JNI_TRUE)) && caps.argumentBuffers;
  g_gpuDrivenEnabled = (icb == JNI_TRUE) && caps.indirectCommandBuffers &&
                       requestedArgumentBuffers;
  g_meshShadersActive =
      (meshShaders == JNI_TRUE) && caps.meshShaders && g_meshPipelineCount > 0;
  g_useArgumentBuffers = requestedArgumentBuffers;
  g_useProgrammableBlending = (progBlend == JNI_TRUE);
  // Depth and OIT are consumed across multiple passes. A memoryless target is
  // only legal after those passes are merged into a single tile-local pass.
  g_useMemorylessTargets = false;

  if (prevMemoryless != g_useMemorylessTargets) {
    g_allocatedRenderWidth = 0;
    g_allocatedRenderHeight = 0;
    ensure_offscreen();
  }
  dbg("nSetFeatureFlags: ICB=%d mesh=%d argBuf=%d OIT=%d memoryless=%d\n",
      g_gpuDrivenEnabled ? 1 : 0, g_meshShadersActive ? 1 : 0,
      g_useArgumentBuffers ? 1 : 0, g_useProgrammableBlending ? 1 : 0,
      g_useMemorylessTargets ? 1 : 0);
  NSLog(@"[MetalRender] FEATURES SET — ICB(GPU-driven)=%@ | MeshShaders=%@ | "
        @"ArgumentBuffers=%@ | OIT/ProgBlend=%@ | Memoryless=%@",
        g_gpuDrivenEnabled ? @"ON" : @"OFF",
        g_meshShadersActive ? @"ON" : @"OFF",
        g_useArgumentBuffers ? @"ON" : @"OFF",
        g_useProgrammableBlending ? @"ON" : @"OFF",
        g_useMemorylessTargets ? @"ON" : @"OFF");
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawDeferredWaterPass(
    JNIEnv *, jclass, jlong frameContext) {
  (void)frameContext;
  if (g_useProgrammableBlending)
    return;
  if (!g_currentEncoder || !g_pipelineInhouse || !g_depthStateNoWrite)
    return;
  if (g_thermalQualityLevel >= 2)
    return;
  if (!g_deferredWaterCmds || g_deferredWaterCmdCount <= 0)
    return;
  if (!g_deferredWaterIB || !g_deferredWaterOffsetBuf)
    return;

  @autoreleasepool {
    static const int VERTEX_STRIDE = 16;

    id<MTLRenderPipelineState> waterPipeline =
        g_pipelineWater ? g_pipelineWater : g_pipelineInhouse;
    [g_currentEncoder setRenderPipelineState:waterPipeline];
    g_currentPipeline = waterPipeline;
    if (g_blockAtlas)
      [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
    if (g_megaVB)
      [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
    [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
    [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
    float camPos[4] = {0.0f, 0.0f, 0.0f, g_skyBrightness};
    [g_currentEncoder setVertexBytes:camPos length:16 atIndex:3];
    [g_currentEncoder setVertexBuffer:g_deferredWaterOffsetBuf
                               offset:0
                              atIndex:4];
    [g_currentEncoder setFragmentBytes:g_entityOverlayParams
                                length:sizeof(g_entityOverlayParams)
                               atIndex:5];
    [g_currentEncoder setDepthStencilState:g_depthStateNoWrite];
    [g_currentEncoder setCullMode:MTLCullModeNone];
    [g_currentEncoder setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
    if (g_deferredWaterCmdCount > 1) {
      std::stable_sort(
          g_deferredWaterCmds, g_deferredWaterCmds + g_deferredWaterCmdCount,
          [](const DeferredWaterCmd &a, const DeferredWaterCmd &b) {
            return a.distSq > b.distSq;
          });
    }
    int waterDraws = 0;
    for (int i = 0; i < g_deferredWaterCmdCount; i++) {
      const DeferredWaterCmd &cmd = g_deferredWaterCmds[i];
      int waterIdxCount = cmd.idxCount - cmd.opaqueIdxCount;
      if (waterIdxCount <= 0)
        continue;
      int opaqueVertCount = cmd.opaqueIdxCount / 6 * 4;
      if (cmd.isMega) {
        [g_currentEncoder
            drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                       indexCount:(NSUInteger)waterIdxCount
                        indexType:MTLIndexTypeUInt32
                      indexBuffer:g_deferredWaterIB
                indexBufferOffset:g_deferredWaterIBOffset
                    instanceCount:1
                       baseVertex:(NSInteger)(cmd.megaOffset / VERTEX_STRIDE) +
                                  opaqueVertCount
                     baseInstance:(NSUInteger)cmd.instanceIdx];
      } else if (cmd.resolvedBuf) {
        [g_currentEncoder setVertexBuffer:cmd.resolvedBuf offset:0 atIndex:0];
        [g_currentEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                     indexCount:(NSUInteger)waterIdxCount
                                      indexType:MTLIndexTypeUInt32
                                    indexBuffer:g_deferredWaterIB
                              indexBufferOffset:g_deferredWaterIBOffset
                                  instanceCount:1
                                     baseVertex:opaqueVertCount
                                   baseInstance:(NSUInteger)cmd.instanceIdx];
        if (g_megaVB)
          [g_currentEncoder setVertexBuffer:g_megaVB offset:0 atIndex:0];
      }
      waterDraws++;
      g_drawCallCount++;
    }

    if (g_depthState)
      [g_currentEncoder setDepthStencilState:g_depthState];
    [g_currentEncoder setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
    [g_currentEncoder setCullMode:MTLCullModeBack];
    if (g_frameCount < 5 || g_frameCount % 600 == 0) {
      dbg("Deferred water pass: drew %d translucent chunk draws\n", waterDraws);
    }
    g_deferredWaterCmdCount = 0;
    g_deferredWaterIB = nil;
    g_deferredWaterIBOffset = 0;
    g_deferredWaterOffsetBuf = nil;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDrawOITPass(
    JNIEnv *, jclass, jlong handle) {
  (void)handle;
  if (!g_useProgrammableBlending)
    return;
  if (!g_device || !g_currentCmdBuffer || !g_frameColorTarget)
    return;
  if (!g_oitAccumTex || !g_oitRevealTex)
    return;
  if (!g_pipelineOITAccum || !g_pipelineOITComposite)
    return;
  if (g_oitCmdsCount <= 0)
    return;

  @autoreleasepool {

    if (g_currentEncoder) {
      [g_currentEncoder endEncoding];
      [g_currentEncoder release];
      g_currentEncoder = nil;
    }
    if (kHiZPathValidated && g_currentCmdBuffer &&
        g_hizDownsamplePipeline && g_hizPyramid &&
        !g_useMemorylessTargets) {
      id<MTLComputeCommandEncoder> hizEnc =
          [g_currentCmdBuffer computeCommandEncoder];
      if (hizEnc) {
        [hizEnc setComputePipelineState:g_hizDownsamplePipeline];
        id<MTLTexture> srcDepth = g_frameDepthTarget;
        if (srcDepth) {
          [hizEnc setTexture:srcDepth atIndex:0];
          [hizEnc setTexture:g_hizPyramid atIndex:1];
          hizUpdateThreadgroupSize(srcDepth);
          [hizEnc dispatchThreadgroups:g_hizGroups
                 threadsPerThreadgroup:g_hizThreads];
        }
        [hizEnc endEncoding];
      }
    }

    {
      MTLRenderPassDescriptor *rp =
          [MTLRenderPassDescriptor renderPassDescriptor];

      rp.colorAttachments[0].texture = g_oitAccumTex;
      rp.colorAttachments[0].loadAction = MTLLoadActionClear;
      rp.colorAttachments[0].storeAction = MTLStoreActionStore;
      rp.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);

      rp.colorAttachments[1].texture = g_oitRevealTex;
      rp.colorAttachments[1].loadAction = MTLLoadActionClear;
      rp.colorAttachments[1].storeAction = MTLStoreActionStore;
      rp.colorAttachments[1].clearColor = MTLClearColorMake(1, 0, 0, 0);

      if (g_frameDepthTarget) {
        rp.depthAttachment.texture = g_frameDepthTarget;
        rp.depthAttachment.loadAction = MTLLoadActionLoad;
        rp.depthAttachment.storeAction = MTLStoreActionStore;
      }
      id<MTLRenderCommandEncoder> enc =
          [g_currentCmdBuffer renderCommandEncoderWithDescriptor:rp];
      if (enc) {
        [enc setRenderPipelineState:g_pipelineOITAccum];
        if (g_depthStateNoWrite)
          [enc setDepthStencilState:g_depthStateNoWrite];
        [enc setCullMode:MTLCullModeNone];
        MTLViewport vp;
        vp.originX = 0;
        vp.originY = 0;
        vp.width = (double)g_oitAccumTex.width;
        vp.height = (double)g_oitAccumTex.height;
        vp.znear = 0.0;
        vp.zfar = 1.0;
        [enc setViewport:vp];

        [enc setVertexBytes:g_projMatrix length:64 atIndex:1];
        [enc setVertexBytes:g_mvMatrix length:64 atIndex:2];
        float camPos[4] = {(float)g_camX, (float)g_camY, (float)g_camZ,
                           g_skyBrightness};
        [enc setVertexBytes:camPos length:16 atIndex:3];
        if (g_blockAtlas)
          [enc setFragmentTexture:g_blockAtlas atIndex:0];
        if (g_megaVB)
          [enc setVertexBuffer:g_megaVB offset:0 atIndex:0];
        if (g_oitOffsetBuf)
          [enc setVertexBuffer:g_oitOffsetBuf offset:0 atIndex:4];
        static const int VERTEX_STRIDE = 16;
        for (int i = 0; i < g_oitCmdsCount; i++) {
          const OITCachedCmd &c = g_oitCmds[i];
          if (c.translucentIdxCount <= 0)
            continue;
          if (c.isMega && g_megaVB) {
            [enc drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                            indexCount:(NSUInteger)c.translucentIdxCount
                             indexType:MTLIndexTypeUInt32
                           indexBuffer:g_oitIB
                     indexBufferOffset:g_oitIBOffset
                         instanceCount:1
                            baseVertex:(NSInteger)(c.megaOffset /
                                                   VERTEX_STRIDE) +
                                       c.opaqueVertCount
                          baseInstance:(NSUInteger)c.instanceIdx];
          } else if (c.resolvedBuf) {
            [enc setVertexBuffer:c.resolvedBuf offset:0 atIndex:0];
            [enc drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                            indexCount:(NSUInteger)c.translucentIdxCount
                             indexType:MTLIndexTypeUInt32
                           indexBuffer:g_oitIB
                     indexBufferOffset:g_oitIBOffset
                         instanceCount:1
                            baseVertex:c.opaqueVertCount
                          baseInstance:(NSUInteger)c.instanceIdx];
            if (g_megaVB)
              [enc setVertexBuffer:g_megaVB offset:0 atIndex:0];
          }
          g_drawCallCount++;
        }
        [enc endEncoding];
      }
    }

    {
      MTLRenderPassDescriptor *cRp =
          [MTLRenderPassDescriptor renderPassDescriptor];
      cRp.colorAttachments[0].texture = g_frameColorTarget;
      cRp.colorAttachments[0].loadAction = MTLLoadActionLoad;
      cRp.colorAttachments[0].storeAction = MTLStoreActionStore;
      id<MTLRenderCommandEncoder> cEnc =
          [g_currentCmdBuffer renderCommandEncoderWithDescriptor:cRp];
      if (cEnc) {
        [cEnc setRenderPipelineState:g_pipelineOITComposite];
        [cEnc setFragmentTexture:g_oitAccumTex atIndex:0];
        [cEnc setFragmentTexture:g_oitRevealTex atIndex:1];

        [cEnc drawPrimitives:MTLPrimitiveTypeTriangle
                 vertexStart:0
                 vertexCount:3];
        g_drawCallCount++;
        [cEnc endEncoding];
      }
    }

    {
      static MTLRenderPassDescriptor *s_resumeRP = nil;
      if (!s_resumeRP) {
        s_resumeRP = [[MTLRenderPassDescriptor renderPassDescriptor] retain];
        s_resumeRP.colorAttachments[0].storeAction = MTLStoreActionStore;
        s_resumeRP.depthAttachment.clearDepth = 0.0;
      }
      s_resumeRP.colorAttachments[0].texture = g_frameColorTarget;
      s_resumeRP.colorAttachments[0].loadAction = MTLLoadActionLoad;

      if (g_frameDepthTarget) {
        s_resumeRP.depthAttachment.texture = g_frameDepthTarget;
        s_resumeRP.depthAttachment.loadAction = MTLLoadActionLoad;
        s_resumeRP.depthAttachment.storeAction = MTLStoreActionStore;
      } else {
        s_resumeRP.depthAttachment.texture = nil;
        s_resumeRP.depthAttachment.loadAction = MTLLoadActionDontCare;
        s_resumeRP.depthAttachment.storeAction = MTLStoreActionDontCare;
      }
      g_currentEncoder = [[g_currentCmdBuffer
          renderCommandEncoderWithDescriptor:s_resumeRP] retain];
      if (g_currentEncoder) {
        MTLViewport vp;
        vp.originX = 0;
        vp.originY = 0;
        vp.width = (double)g_frameColorTarget.width;
        vp.height = (double)g_frameColorTarget.height;
        vp.znear = 0.0;
        vp.zfar = 1.0;
        [g_currentEncoder setViewport:vp];
        [g_currentEncoder setVertexBytes:g_projMatrix length:64 atIndex:1];
        [g_currentEncoder setVertexBytes:g_mvMatrix length:64 atIndex:2];
        float camPos[4] = {(float)g_camX, (float)g_camY, (float)g_camZ,
                           g_skyBrightness};
        [g_currentEncoder setVertexBytes:camPos length:16 atIndex:3];
        float chunkOff[4] = {0, 0, 0, 0};
        [g_currentEncoder setVertexBytes:chunkOff length:16 atIndex:4];
        [g_currentEncoder setFrontFacingWinding:MTLWindingCounterClockwise];
        [g_currentEncoder setCullMode:MTLCullModeBack];
        if (g_blockAtlas)
          [g_currentEncoder setFragmentTexture:g_blockAtlas atIndex:0];
        g_currentPipeline = nil;
      }
    }
    dbg("OIT pass: drew %d translucent cmds\n", g_oitCmdsCount);
    g_oitCmdsCount = 0;
  }
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetAvailableMemory(
    JNIEnv *, jclass) {
  mach_port_t host = mach_host_self();
  vm_size_t pageSize;
  host_page_size(host, &pageSize);
  vm_statistics64_data_t vmStats;
  mach_msg_type_number_t count = HOST_VM_INFO64_COUNT;
  if (host_statistics64(host, HOST_VM_INFO64, (host_info64_t)&vmStats,
                        &count) == KERN_SUCCESS) {
    uint64_t freePages = vmStats.free_count + vmStats.inactive_count;
    return (jlong)(freePages * pageSize);
  }

  return (jlong)g_megaVBBudget;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetHiZMipCount(
    JNIEnv *, jclass) {
  return (jint)g_hizMipCount;
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetGPUCullStats(
    JNIEnv *env, jclass, jintArray outStats) {
  if (!outStats || !g_cullStatsBuffer)
    return;
  if (env->GetArrayLength(outStats) < 5)
    return;
  uint32_t *stats = (uint32_t *)[g_cullStatsBuffer contents];
  jint jstats[5] = {(jint)stats[0], (jint)stats[1], (jint)stats[2],
                    (jint)stats[3], (jint)stats[4]};
  env->SetIntArrayRegion(outStats, 0, 5, jstats);
}
extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUploadChunkUniforms(
    JNIEnv *env, jclass, jlong handle, jobject directBuffer, jint count) {
  (void)handle;
  if (!g_device || !directBuffer || count <= 0)
    return;
  void *ptr = env->GetDirectBufferAddress(directBuffer);
  jlong cap = env->GetDirectBufferCapacity(directBuffer);
  if (!ptr)
    return;
  size_t entrySize = 16;
  size_t totalSize = (size_t)count * entrySize;
  if ((size_t)cap < totalSize)
    return;
  if (!g_chunkUniformsBuffer || g_chunkUniformsBuffer.length < totalSize) {
    if (g_chunkUniformsBuffer)
      [g_chunkUniformsBuffer release];
    g_chunkUniformsBuffer = [g_device newBufferWithLength:totalSize
                                                  options:MTLStorageModeShared];
  }
  memcpy([g_chunkUniformsBuffer contents], ptr, totalSize);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nAreMeshShadersActive(
    JNIEnv *, jclass) {
  return g_meshShadersActive ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsGPUDrivenActive(
    JNIEnv *, jclass) {
  return g_gpuDrivenEnabled ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nAreArgumentBuffersActive(
    JNIEnv *, jclass) {
  return g_useArgumentBuffers ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nAreMemorylessTargetsActive(
    JNIEnv *, jclass) {
  return g_useMemorylessTargets ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nFlushDeferredDeletions(
    JNIEnv *, jclass) {
  @autoreleasepool {
    int freed = 0;
    {
      std::lock_guard<std::mutex> dLock(g_deferredMutex);
      for (auto &dd : g_deferredDeletions) {
        if (dd.isMega) {
          megaFree(dd.handle);
        } else {
          std::unique_lock<std::shared_mutex> bufLock(g_bufferMutex);
          auto it = g_buffers.find(dd.handle);
          if (it != g_buffers.end()) {
            [it->second release];
            g_buffers.erase(it);
            auto sizeIt = g_bufferSizes.find(dd.handle);
            if (sizeIt != g_bufferSizes.end()) {
              g_individualBufferBytes -=
                  std::min(g_individualBufferBytes, sizeIt->second);
              g_bufferSizes.erase(sizeIt);
            }
          }
        }
        freed++;
      }
      g_deferredDeletions.clear();
    }

    {
      std::unique_lock<std::shared_mutex> mLock(g_megaMutex);
      megaCoalesceFreeList();
    }
    dbg("nFlushDeferredDeletions: freed %d buffers, mega free list coalesced\n",
        freed);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nClearAllChunkRegistrations(
    JNIEnv *, jclass) {
  std::unique_lock<std::shared_mutex> lock(g_meshRegMutex);

  for (size_t i = 0; i < g_nativeMeshes.size(); i++) {
    g_nativeMeshes[i].active = false;
  }

  g_meshFreeSlots.clear();
  for (size_t i = 0; i < g_nativeMeshes.size(); i++) {
    g_meshFreeSlots.push_back(i);
  }

  g_meshKeyToIdx.clear();

  g_hasStaleDrawList = false;
  g_staleDrawCount = 0;
  g_staleMegaCount = 0;
  g_activeMeshIndices.clear();
  g_activeMeshCount = 0;
  dbg("nClearAllChunkRegistrations: cleared all %zu mesh slots\n",
      g_nativeMeshes.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nFlushFrames(
    JNIEnv *, jclass) {
  @autoreleasepool {
    dbg("nFlushFrames: draining all in-flight GPU frames\n");
    drain_surface_slots(true);
    g_gpuNeedsRecovery.store(false, std::memory_order_release);
    dbg("nFlushFrames: complete; GPU and GL ownership drained\n");
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nWatchdogReset(
    JNIEnv *, jclass) {
  @autoreleasepool {
    // Never fabricate completion or make an in-flight IOSurface reusable. The
    // next frame performs a real drain before recreating the command queue.
    dbg("WATCHDOG: recovery requested; preserving slot ownership\n");
    g_gpuNeedsRecovery.store(true, std::memory_order_release);
    g_surfaceSlotChanged.notify_all();
  }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetGpuFrameTimeMs(
    JNIEnv *, jclass) {
  return g_lastGpuMs.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetGpuCommandBufferErrorCount(
    JNIEnv *, jclass) {
  return (jlong)g_gpuCommandBufferErrorCount.load(std::memory_order_acquire);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetInFlightFrameTimeoutCount(
    JNIEnv *, jclass) {
  return (jlong)g_inFlightFrameTimeoutCount.load(std::memory_order_acquire);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetNoIOSurfaceSlotSkipCount(
    JNIEnv *, jclass) {
  return (jlong)g_noIOSurfaceSlotSkipCount.load(std::memory_order_acquire);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMslCompileAttemptCount(
    JNIEnv *, jclass) {
  return (jlong)g_irisMslCompileAttemptCount.load(std::memory_order_acquire);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMslCompileSuccessCount(
    JNIEnv *, jclass) {
  return (jlong)g_irisMslCompileSuccessCount.load(std::memory_order_acquire);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMslCompileUnsupportedCount(
    JNIEnv *, jclass) {
  return (jlong)g_irisMslCompileUnsupportedCount.load(
      std::memory_order_acquire);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMslCompileFailureCount(
    JNIEnv *, jclass) {
  return (jlong)g_irisMslCompileFailureCount.load(std::memory_order_acquire);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMslLiveLibraryCount(
    JNIEnv *, jclass) {
  return (jlong)g_irisMslLiveLibraryCount.load(std::memory_order_acquire);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nAreResidencySetsSupported(
    JNIEnv *, jclass) {
#if defined(__aarch64__) && (__MAC_OS_X_VERSION_MAX_ALLOWED >= 150000)
  if (@available(macOS 15.0, *)) {
    return g_device != nil && [g_device supportsFamily:MTLGPUFamilyApple6]
               ? JNI_TRUE
               : JNI_FALSE;
  }
#endif
  return JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nCreateResidencySet(
    JNIEnv *, jclass, jlong device) {
#if defined(__aarch64__) && (__MAC_OS_X_VERSION_MAX_ALLOWED >= 150000)
  if (@available(macOS 15.0, *)) {
    id<MTLDevice> dev = (__bridge id<MTLDevice>)(void *)device;
    if (dev != nil &&
        [dev respondsToSelector:@selector(newResidencySetWithDescriptor:
                                                            error:)]) {
      MTLResidencySetDescriptor *desc =
          [[MTLResidencySetDescriptor alloc] init];
      desc.label = @"entity_atlas_residency";
      NSError *error = nil;
      id<MTLResidencySet> set =
          [dev newResidencySetWithDescriptor:desc error:&error];
      [desc release];
      if (set != nil) {
        return (jlong)(uintptr_t)(__bridge void *)set;
      }
    }
  }
#endif
  return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nUpdateResidencySet(
    JNIEnv *env, jclass, jlong setHandle, jlongArray textureHandles) {
#if defined(__aarch64__) && (__MAC_OS_X_VERSION_MAX_ALLOWED >= 150000)
  if (@available(macOS 15.0, *)) {
    id<MTLResidencySet> set = (__bridge id<MTLResidencySet>)(void *)setHandle;
    if (set == nil)
      return;
    jsize count = env->GetArrayLength(textureHandles);
    jlong *handles = env->GetLongArrayElements(textureHandles, nullptr);
    [set removeAllAllocations];
    for (jsize i = 0; i < count; i++) {
      id<MTLTexture> tex = (__bridge id<MTLTexture>)(void *)handles[i];
      if (tex != nil)
        [set addAllocation:(id<MTLAllocation>)tex];
    }
    env->ReleaseLongArrayElements(textureHandles, handles, JNI_ABORT);
    [set commit];
  }
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDestroyResidencySet(
    JNIEnv *, jclass, jlong setHandle) {
#if defined(__aarch64__) && (__MAC_OS_X_VERSION_MAX_ALLOWED >= 150000)
  if (@available(macOS 15.0, *)) {
    if (setHandle != 0) {
      id<MTLResidencySet> set =
          (__bridge id<MTLResidencySet>)(void *)setHandle;
      [set release];
    }
  }
#endif
}

static std::unordered_map<uint64_t, id<MTLIndirectCommandBuffer>> g_javaICBs;
static std::unordered_map<uint64_t, NSUInteger> g_javaICBEncodedCount;
static uint64_t g_nextJavaICBHandle = 0xA000000000000000ULL;
static std::mutex g_javaICBMutex;

extern "C" JNIEXPORT jlong JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nCreateIndirectCommandBuffer(
    JNIEnv *, jclass, jlong deviceHandle, jint maxCommands) {
  (void)deviceHandle;
  ensure_device();
  if (!g_device || maxCommands <= 0)
    return 0;
  MTLIndirectCommandBufferDescriptor *desc =
      [MTLIndirectCommandBufferDescriptor new];
  desc.commandTypes = MTLIndirectCommandTypeDrawIndexed;
  desc.inheritPipelineState = YES;
  desc.inheritBuffers = YES;
  desc.maxVertexBufferBindCount = 8;
  id<MTLIndirectCommandBuffer> icb =
      [g_device newIndirectCommandBufferWithDescriptor:desc
                                       maxCommandCount:(NSUInteger)maxCommands
                                               options:MTLStorageModeShared];
  [desc release];
  if (!icb)
    return 0;
  std::lock_guard<std::mutex> lock(g_javaICBMutex);
  uint64_t h = g_nextJavaICBHandle++;
  g_javaICBs[h] = icb;
  g_javaICBEncodedCount[h] = 0;
  return (jlong)h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nEncodeChunkDrawICBCmd(
    JNIEnv *, jclass, jlong icbHandle, jint cmdIndex, jint sectionIndex,
    jint instanceCount, jlong meshBufferHandle, jlong indexBufferHandle,
    jint indexCount) {
  (void)sectionIndex;
  std::lock_guard<std::mutex> lock(g_javaICBMutex);
  auto it = g_javaICBs.find((uint64_t)icbHandle);
  if (it == g_javaICBs.end())
    return;
  id<MTLIndirectCommandBuffer> icb = it->second;
  if (!icb || cmdIndex < 0 || cmdIndex >= (int)[icb size])
    return;
  id<MTLIndirectRenderCommand> icmd =
      [icb indirectRenderCommandAtIndex:(NSUInteger)cmdIndex];
  ResolvedBuf vbRes = resolve_buffer((uint64_t)meshBufferHandle);
  ResolvedBuf ibRes = resolve_buffer((uint64_t)indexBufferHandle);
  if (!vbRes.buf || !ibRes.buf)
    return;
  [icmd setVertexBuffer:vbRes.buf offset:(NSUInteger)vbRes.offset atIndex:0];
  int inst = std::max((int)instanceCount, 1);
  g_javaICBEncodedCount[(uint64_t)icbHandle] = std::max(
      g_javaICBEncodedCount[(uint64_t)icbHandle], (NSUInteger)(cmdIndex + 1));
  [icmd drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                   indexCount:(NSUInteger)indexCount
                    indexType:MTLIndexTypeUInt32
                  indexBuffer:ibRes.buf
            indexBufferOffset:(NSUInteger)ibRes.offset
                instanceCount:(NSUInteger)inst
                   baseVertex:0
                 baseInstance:0];
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nExecuteIndirectCommandBuffer(
    JNIEnv *, jclass, jlong frameContext, jlong icbHandle) {
  (void)frameContext;
  if (!g_currentEncoder)
    return;
  std::lock_guard<std::mutex> lock(g_javaICBMutex);
  auto it = g_javaICBs.find((uint64_t)icbHandle);
  if (it == g_javaICBs.end())
    return;
  id<MTLIndirectCommandBuffer> icb = it->second;
  if (!icb)
    return;
  auto cit = g_javaICBEncodedCount.find((uint64_t)icbHandle);
  NSUInteger count = (cit != g_javaICBEncodedCount.end()) ? cit->second : 0;
  if (count > 0) {
    [g_currentEncoder executeCommandsInBuffer:icb
                                    withRange:NSMakeRange(0, count)];
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nDestroyIndirectCommandBuffer(
    JNIEnv *, jclass, jlong icbHandle) {
  std::lock_guard<std::mutex> lock(g_javaICBMutex);
  auto it = g_javaICBs.find((uint64_t)icbHandle);
  if (it != g_javaICBs.end()) {
    [it->second release];
    g_javaICBs.erase(it);
  }
  auto cit = g_javaICBEncodedCount.find((uint64_t)icbHandle);
  if (cit != g_javaICBEncodedCount.end()) {
    g_javaICBEncodedCount.erase(cit);
  }
}
