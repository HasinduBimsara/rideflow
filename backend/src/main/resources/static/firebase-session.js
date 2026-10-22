import {
  initializeApp,
  getApps,
  getApp,
} from "https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js";

import { getAuth } from "https://www.gstatic.com/firebasejs/12.19.0/firebase-auth.js";

const firebaseConfig = {
  apiKey: "AIzaSyAvw6oH-QXrnyF3nVQN8RSSq0g-nLC7CM8",
  authDomain: "rideflow-3dd91.firebaseapp.com",
  projectId: "rideflow-3dd91",
  storageBucket: "rideflow-3dd91.firebasestorage.app",
  messagingSenderId: "339723920460",
  appId: "1:339723920460:web:90d2fad70f8b07307740e9",
};

const app = getApps().some((app) => app.name === "[DEFAULT]")
  ? getApp()
  : initializeApp(firebaseConfig);

export const auth = getAuth(app);
