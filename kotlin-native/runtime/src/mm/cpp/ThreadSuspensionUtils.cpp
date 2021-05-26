/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "ThreadData.hpp"
#include "ThreadState.hpp"
#include "ThreadSuspensionUtils.hpp"

#include <thread>

namespace {

// TODO: Accept a ThreadSuspensionData?
bool isSuspendedOrNative(kotlin::mm::ThreadData& thread) {
    auto& suspensionData = thread.suspensionData();
    return suspensionData.suspended() || suspensionData.state() == kotlin::ThreadState::kNative;
}

bool isNotSuspendedOrNative(kotlin::mm::ThreadData& thread) {
    auto& suspensionData = thread.suspensionData();
    return !suspensionData.suspended() || suspensionData.state() == kotlin::ThreadState::kNative;
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

bool kotlin::mm::ThreadSuspensionData::suspendIfRequested() noexcept {
    if (IsThreadSuspensionRequested()) {
        std::unique_lock lock(gSuspensionMutex);
        if (IsThreadSuspensionRequested()) {
            suspended_ = true;
            gSuspendsionCondVar.wait(lock, []() { return !IsThreadSuspensionRequested(); });
            suspended_ = false;
            return true;
        }
    }
    return false;
}

bool kotlin::mm::IsThreadSuspensionRequested() {
    // TODO: Consider using a more relaxed memory order.
    return gSuspensionRequested.load();
}

void kotlin::mm::SuspendThreads() {
    {
        std::unique_lock lock(gSuspensionMutex);
        gSuspensionRequested = true;
    }

    // Spin wating for threads to suspend. Ignore Native threads.
    while(!allThreads(isSuspendedOrNative)) {
        yield();
    }
}

void kotlin::mm::ResumeThreads() {
    // From the std::condition_variable docs:
    // Even if the shared variable is atomic, it must be modified under
    // the mutex in order to correctly publish the modification to the waiting thread.
    // https://en.cppreference.com/w/cpp/thread/condition_variable
    {
        std::unique_lock lock(gSuspensionMutex);
        gSuspensionRequested = false;
    }
    gSuspendsionCondVar.notify_all();
}
