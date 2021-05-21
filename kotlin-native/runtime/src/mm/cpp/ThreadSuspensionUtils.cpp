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

template<typename F>
bool allThreads(F predicate) {
    kotlin::mm::ThreadRegistry::Iterable threads = kotlin::mm::ThreadRegistry::Instance().Iter();
    for (auto& thread : threads) {
        if (!predicate(thread)) {
            return false;
        }
    }
    return true;
}

void yield() {
    std::this_thread::yield();
}

std::atomic<bool> gSuspensionRequested = false;
std::mutex gSuspensionMutex;
std::condition_variable gSuspendsionCondVar;

} // namespace

bool kotlin::mm::IsThreadSuspensionRequested() {
    // TODO: Consider using a more relaxed memory order.
    return gSuspensionRequested.load();
}

void kotlin::mm::SuspendThreadIfRequested(ThreadData* threadData) {
    if (IsThreadSuspensionRequested()) {
        std::unique_lock lock(gSuspensionMutex);
        if (IsThreadSuspensionRequested()) {
            ThreadStateGuard stateGuard(ThreadState::kSuspended);
            gSuspendsionCondVar.wait(lock, []() { return !IsThreadSuspensionRequested(); });
        }
    }
}

void kotlin::mm::SuspendThreads() {
    gSuspensionRequested = true;

    // Spin wating for threads to suspend. Ignore Native threads.
    while(!allThreads(isSuspendedOrNative)) {
        yield();
    }
}

void kotlin::mm::ResumeThreads() {
    RuntimeAssert(allThreads(isSuspendedOrNative), "Some threads are in RUNNABLE state");

    // From the std::condition_variable docs:
    // Even if the shared variable is atomic, it must be modified under
    // the mutex in order to correctly publish the modification to the waiting thread.
    // https://en.cppreference.com/w/cpp/thread/condition_variable
    {
        std::unique_lock lock(gSuspensionMutex);
        gSuspensionRequested = false;
    }
    gSuspendsionCondVar.notify_all(); // TODO: Shouldn't it be under the lock too?

    // Wait for threads to run. Ignore Native threads.
    // TODO: This loop (+ GC lock) allows us to avoid the situation when a resumed thread triggers the GC again while we still resuming other threads.
    //       Try to get rid of this?
    while(!allThreads(isRunnableOrNative)) {
        yield();
    }
}
