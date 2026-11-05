# Copyright (C) 2023 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# This is required by TProxyService at scan startup. A wildcard include silently
# builds an APK without the VPN library when a new worktree has no submodules.
TUNNEL_MAKEFILE := $(call my-dir)/hev-socks5-tunnel/Android.mk
ifeq ($(wildcard $(TUNNEL_MAKEFILE)),)
    $(error Scan VPN sources are missing. Run: git submodule update --init --recursive)
endif
include $(TUNNEL_MAKEFILE)
