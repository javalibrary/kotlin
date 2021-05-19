/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "ThreadData.hpp"
#include "ThreadState.hpp"
#include "ThreadSuspensionUtils.hpp"

#include <thread>

namespace {

bool isSuspendedOrNative(kotlin::mm::ThreadData& thread) {
    auto state = thread.state();
    return state == kotlin::ThreadState::kSuspended || state == kotlin::ThreadState::kNative;
}

bool isRunnableOrNative(kotlin::mm::ThreadData& thread) {
    auto state = thread.state();
    return state == kotlin::ThreadState::kRunnable || state == kotlin::ThreadState::kNative;
}

template<bool(*Check)(kotlin::mm::ThreadData&)>
bool allThreads() {
    kotlin::mm::ThreadRegistry::Iterable threads = kotlin::mm::ThreadRegistry::Instance().Iter();
    for (auto& thread : threads) {
        if (!Check(thread)) {
            return false;
        }
    }
    return true;
}

void yield() {
    std::this_thread::yield();
}

std::atomic<bool> gSuspensionRequested = false;

} // namespace

bool kotlin::mm::IsThreadSuspensionRequested() {
    return gSuspensionRequested.load(); // TODO: Play with memory orders.
}

void kotlin::mm::SuspendThreadIfRequested(ThreadData* threadData) {
    if (IsThreadSuspensionRequested()) {
        std::unique_lock lock(threadData->suspendMutex());

        if (IsThreadSuspensionRequested()) {
            AssertThreadState(threadData, {ThreadState::kRunnable, ThreadState::kNative});
            ThreadStateGuard stateGuard(ThreadState::kSuspended);
            threadData->suspendCondition().wait(lock);
        }
    }
}

void kotlin::mm::SuspendThreads() {
    gSuspensionRequested = true;

    // Spin wating for threads to suspend. Ignore Native threads.
    while(!allThreads<isSuspendedOrNative>()) {
        yield();
    }
}

void kotlin::mm::ResumeThreads() {
    gSuspensionRequested = false;
    {
        auto threads = ThreadRegistry::Instance().Iter();
        for (auto& thread : threads) {
            AssertThreadState(&thread, {ThreadState::kNative, ThreadState::kSuspended});
            std::unique_lock lock(thread.suspendMutex());
            if (thread.state() == ThreadState::kSuspended) {
                thread.suspendCondition().notify_one();
            }
        }
    }

    // Wait for threads to run. Ignore Native threads.
    // TODO: This (+ GC lock) should allow us to avoid the situation when a resumed thread triggers the GC again while we still resuming other threads.
    //       Try to get rid for this?
    while(!allThreads<isRunnableOrNative>()) {
        yield();
    }
}
