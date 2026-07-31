<template>
  <div class="common-layout">
    <el-container>
      <el-header height="68px" style="padding: 0">
        <div class="header">
          <div @click="toMySpace" class="logo">
            <span class="logo-mark" aria-hidden="true"><i></i><i></i><i></i></span>
            <span class="logo-copy">
              <strong>QuickLink</strong>
              <small>短链接管理平台</small>
            </span>
          </div>
          <div class="header-actions">
            <button class="workspace-button" type="button" @click="toMySpace">
              <el-icon><DataLine /></el-icon>
              链接工作台
            </button>
            <el-dropdown>
              <div class="block">
                <span class="user-avatar">{{ username?.slice(0, 1)?.toUpperCase() }}</span>
                <span class="name-span">{{ username }}</span>
                <el-icon class="dropdown-icon"><ArrowDown /></el-icon>
              </div>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="toMine">个人信息</el-dropdown-item>
                  <el-dropdown-item divided @click="logout">退出</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>
      </el-header>
      <el-main style="padding: 0">
        <div class="content-box">
          <RouterView class="content-space" />
        </div>
      </el-main>
      <!-- <el-container>
        <el-aside width="180px">
          <el-menu
            active-text-color="#073372"
            background-color="#0e5782"
            class="el-menu-vertical-demo"
            :default-active="getLasteRoute(route.path)"
            text-color="#fff"
            @select="handleSelect"
          >
            <template v-for="item in menuInfos" :key="item.name">
              <el-menu-item :index="item.path">
                <el-icon><icon-menu /></el-icon>
                <span>{{ item.name }}</span>
              </el-menu-item>
            </template>
          </el-menu></el-aside
        >

      </el-container> -->
    </el-container>
  </div>
</template>

<script setup>
import { ref, getCurrentInstance, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { removeKey, removeUsername, getToken, getUsername } from '@/core/auth.js'
import { ElMessage } from 'element-plus'
const { proxy } = getCurrentInstance()
const API = proxy.$API
// 当当前路径和菜单不匹配时，菜单不会被选中
const router = useRouter()
const toMine = () => {
  router.push('/home' + '/account')
}
// 登出
const logout = async () => {
  const token = getToken()
  const username = getUsername()
  // 请求登出的接口
  await API.user.logout({ token, username })
  // 删除cookies中的token和username
  removeUsername()
  removeKey()
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  router.push('/login')
  ElMessage.success('成功退出！')
}
// 点击左上方的图片跳转到我的空间
const toMySpace = () => {
  router.push('/home' + '/space')
}
const username = ref('')
onMounted(async () => {
  const actualUsername = getUsername()
  await API.user.queryUserInfo(actualUsername)
  username.value = truncateText(actualUsername, 8)
})

// 辅助函数，用于截断文本
const truncateText = (text, maxLength) => {
  return text.length > maxLength ? text.slice(0, maxLength) + '...' : text
}
</script>

<style lang="scss" scoped>
.el-container {
  height: 100vh;

  .el-aside {
    border: 0;
    background-color: #0e5782;

    ul {
      border: 0px;
    }
  }

  .el-main {
    background-color: #f3f6fb;
  }
}

.header {
  color: #172033;
  background: rgba(255, 255, 255, 0.94);
  border-bottom: 1px solid #e8ecf4;
  padding: 0 28px;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 1px 12px rgba(28, 45, 91, 0.04);

  .block {
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 9px;
    padding: 7px 9px 7px 7px;
    border: 1px solid transparent;
    border-radius: 12px;
    transition: 0.2s ease;
  }

  .block:hover {
    border-color: #e3e8f2;
    background: #f7f9fd;
  }
}

.content-box {
  height: calc(100vh - 68px);
  background-color: #f3f6fb;
}

:deep(.el-tooltip__trigger:focus-visible) {
  outline: unset;
}

.logo {
  display: flex;
  align-items: center;
  gap: 11px;
  color: #172033;
  cursor: pointer;
}

.logo:hover {
  color: #2949b9;
}

.logo-mark {
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  border-radius: 11px;
  background: #2dd4bf;

  i {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: #17265c;
  }

  i:nth-child(2) {
    width: 9px;
    border-radius: 5px;
  }
}

.logo-copy {
  display: flex;
  flex-direction: column;
  line-height: 1.15;

  strong {
    font-size: 17px;
    font-weight: 750;
    letter-spacing: -0.3px;
  }

  small {
    margin-top: 3px;
    color: #8993a7;
    font-size: 10px;
    letter-spacing: 0.08em;
  }
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 20px;
}

.workspace-button {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 13px;
  border: 0;
  border-radius: 10px;
  color: #536078;
  background: #f5f7fb;
  font-size: 13px;
  cursor: pointer;
}

.workspace-button:hover {
  color: #2949b9;
  background: #eef2ff;
}

.link-span {
  color: #fff;
  opacity: .6;
  margin-right: 30px;
  font-size: 16px;
  font-family: 'Helvetica Neue', Helvetica, STHeiTi, Arial, sans-serif;
  cursor: pointer;
  text-decoration: none;
}

.link-span:hover {
  text-decoration: underline !important;
  opacity: 1;
  color: #fff;
}

.name-span {
  max-width: 120px;
  color: #33405a;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.user-avatar {
  width: 31px;
  height: 31px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  color: #fff;
  background: linear-gradient(135deg, #3758c7, #243b91);
  font-size: 12px;
  font-weight: 700;
}

.dropdown-icon {
  color: #9aa3b5;
  font-size: 12px;
}

@media (max-width: 700px) {
  .header {
    padding: 0 14px;
  }

  .workspace-button,
  .logo-copy small {
    display: none;
  }

  .header-actions {
    gap: 8px;
  }
}
</style>
