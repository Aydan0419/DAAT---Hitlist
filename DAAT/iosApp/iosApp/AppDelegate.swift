import FirebaseCore
import FirebaseMessaging
import GoogleSignIn
import Shared
import UIKit
import UserNotifications

final class AppDelegate: NSObject, UIApplicationDelegate, MessagingDelegate, UNUserNotificationCenterDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Firebase must be configured before any shared Kotlin code touches it.
        FirebaseApp.configure()
        IosBridge.shared.native = NativeBridge()

        Messaging.messaging().delegate = self
        UNUserNotificationCenter.current().delegate = self
        // Harmless if permission hasn't been granted yet; silent pushes still need a device token.
        application.registerForRemoteNotifications()
        return true
    }

    // Google sign-in returns to the app through a URL.
    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        GIDSignIn.sharedInstance.handle(url)
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Messaging.messaging().apnsToken = deviceToken
    }

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        if let token = fcmToken {
            IosBridge.shared.onPushToken(token: token)
        }
    }

    /// The server's silent "where are you?" push: reply with one location fix so a
    /// snipe on this player can be verified while the app is in the background.
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        guard userInfo["type"] as? String == "locate" else {
            completionHandler(.noData)
            return
        }
        IosBridge.shared.reportLocation {
            completionHandler(.newData)
        }
    }

    /// Show "You got sniped" style banners even while the app is open.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }
}
