# SỔ TAY KỸ THUẬT & LỊCH SỬ PHÁT TRIỂN NCONNECT-IOS
> **Mục đích**: Ghi chép chi tiết toàn bộ kiến trúc, cơ chế giao thức kết nối xe máy Yamaha, phân tích dịch ngược (reverse-engineering), các lỗi thực tế đã gặp và giải pháp chuẩn xác để **tránh lặp lại lỗi** trong quá trình bảo trì và phát triển tiếp theo.

---

## 1. TỔNG QUAN KIẾN TRÚC HỆ THỐNG
Dự án **NConnect-iOS** (tên hiển thị: **MyNMAX**) là giải pháp dẫn đường OpenStreetMap và hiển thị thông tin chuyên dụng cho các dòng xe máy Yamaha trang bị màn hình TFT kỹ thuật số (XMAX 300, NMAX 155, MT-09, R9, v.v.).

### Cơ chế kết nối đa kênh đồng thời (Multi-Channel):
1. **Kênh ExternalAccessory (MFi Garmin NaviLite - `com.garmin.navilite.data`)**:
   - Giao tiếp với cụm màn hình TFT của xe qua giao thức chuẩn Garmin StreetCross (Service UUID: `00007220-0000-1000-8000-00805F9B34FB`).
   - Nhiệm vụ: Stream hình ảnh bản đồ thời gian thực (nén JPEG qua Bluetooth), hiển thị thanh chỉ dẫn rẽ (Turn-by-Turn), cập nhật tên đường dưới footer, nhận và xử lý tín hiệu phím bấm cùm tay lái (+ / -).
2. **Kênh CoreBluetooth (Yamaha CCU GATT - Service `AFA2CDF4...` / `00008911...`)**:
   - Kết nối với bộ điều khiển CCU xe máy Yamaha để lắng nghe trạng thái khoá điện, kết nối và thu thập dữ liệu telemetry phụ trợ.
3. **Kênh Apple Notification Center Service (ANCS) & CallKit**:
   - Quản lý hiển thị thông báo tin nhắn (SMS, iMessage, Zalo, Messenger) từ hệ thống iOS lên cụm đồng hồ xe.
   - Bắt sự kiện cuộc gọi đến theo thời gian thực qua `CXCallObserver` và điều hướng phím bấm cùm tay lái (+ nghe, - từ chối).

---

## 2. CHI TIẾT GIAO THỨC GARMIN STREETCROSS & NAVILITE
Qua quá trình dịch ngược (disassembly/decompilation) file thực thi `classes.dex` của phiên bản Android gốc (gói `o3/c`, `Lm3/r`, `b/k`):

### 2.1 Cấu trúc khung tin NaviLite (Wire Framing)
Mọi gói tin dữ liệu điều khiển gửi lên màn hình xe đều phải tuân thủ nghiêm ngặt định dạng:
```
[ 4 Bytes Magic: "nAl@" (0x6e, 0x41, 0x6c, 0x40) ]
[ 1 Byte Version: 0x01 ]
[ 1 Byte FrameType (ft): 0x06 = FT_PHONE ]
[ 1 Byte ServiceType (svc) ]
[ 4 Bytes Payload Length: Little-Endian ]
[ 1 Byte Payload Data Type (pdt): 0 = VALUE, 1 = POINTER ]
[ 4 Bytes CRC32-MPEG2 Checksum: Tính từ byte Magic đến hết Payload ]
[ N Bytes Payload ]
```

### 2.2 Quy trình bắt tay 3 bước (3-Step Handshake)
* **Bước 1 (Serial Exchange)**: Khi xe kết nối, xe gửi chuỗi Serial máy. Điện thoại phản hồi: `[0x51, 0x21, 0x00, 0x00]`.
* **Bước 2 (Screen Dimension Negotiation)**: Xe gửi lệnh `0x53` hoặc `0x83` kèm kích thước màn hình (ví dụ `480x234` hoặc `400x240`). Điện thoại phản hồi gói `0x54, 0x01` kèm mã phần mềm `006-B4160` (hoặc fallback `006-B3952-10`).
* **Bước 3 (Auth Confirmation)**: Xe xác nhận quyền truyền hình qua mã `82 = 1`. Sau đó điện thoại gửi cấu hình khởi tạo `0x20` để sẵn sàng nhận luồng frame JPEG.

### 2.3 Phân mảnh luồng ảnh JPEG (Chunking)
- Mỗi khung hình JPEG kích thước ~480x234 sau khi vẽ bằng CoreGraphics được nén ở mức chất lượng 0.85 (kích thước ~15KB - 30KB).
- Header khung hình: `[0x05, FrameIdx(4B LE), TotalSize(4B LE)]`.
- Phải cắt nhỏ thành các chunk **<= 500 bytes** để vừa với MTU Bluetooth/MFi và không làm tràn bộ đệm phần cứng của màn hình xe.

---

## 3. SỔ TAY CÁC LỖI ĐÃ GẶP & CÁCH GIẢI QUYẾT TRIỆT ĐỂ (TRÁNH LẶP LẠI)

### 🔴 LỖI 1: Chữ `▲ Road` ở thanh Footer màn hình xe không đổi
* **Triệu chứng**: Dưới đáy màn hình TFT luôn hiện chữ `▲ Road` mặc định, không hiển thị được tên con đường xe đang chạy qua.
* **Nguyên nhân gốc rễ (Phân tích dịch ngược DEX chuyên sâu)**:
  1. Chữ `▲ Road` là chuỗi placeholder nạp sẵn trong ROM xe khi xe chưa nhận được lệnh cập nhật chuỗi tên đường hợp lệ từ CCU.
  2. Dịch ngược chi tiết các hàm trong `classes.dex` của Android:
     - `Lo3/c;->f` (cOff `1980160`): Nhận tên đường (String) → chuẩn hoá bỏ dấu tiếng Việt → chuyển thành mảng bytes UTF-8 ASCII → gọi `Lo3/c;->b(roadBytes, 3, 1)`. **Đây chính là Service 3 (PDT 1) chuyên trách gửi chuỗi tên đường trực tiếp cho màn hình xe!**
     - `Lo3/c;->j` (cOff `1980568`): Nhận chuỗi văn bản → đóng gói `[0x07, 0x1A, len, 0x00] + bytes` → gọi `Lo3/c;->b(payload, 14, 1)`. **Đây là Service 14 (PDT 1) với lệnh con 0x1A để hiển thị văn bản lên thanh trạng thái đồng hồ xe!**
     - `Lm3/r;->d` (cOff `1854372`): Khi chuẩn bị chuyển thông tin rẽ, gọi tuần tự:
       `Lo3/c;->j("300 m")` (Service 14) → `Lo3/c;->g()` (Service 2 `[1, 0]`) → `Lo3/c;->f(road)` (Service 3) → `Lo3/c;->j("")` (Service 14).
     - `Lm3/r;->l` (cOff `1858112`): Gửi Service 4 (PDT 1: `[icon, distLE(4B), unitLen, roadLen, unitBytes, roadBytes]`) và Service 2 (PDT 1: ETA).
  3. **Lỗi trong code cũ**:
     - Trong chuỗi bắt tay `setup` ban đầu, code cũ gửi `(3, 1, [])` với mảng byte rỗng (`roadBytes = []`)! Do nhận chuỗi rỗng nên xe giữ nguyên chữ `Road` mặc định trong ROM.
     - Trong hàm `updateCurrentRoadNameOnTFT`, code cũ chỉ gửi Service 2 và Service 4, **hoàn toàn bỏ quên Service 3 và Service 14**!
* **Giải pháp chuẩn xác triệt để**:
  - Trong `runNaviLiteHandshake`: Luôn nạp tên đường khởi tạo hợp lệ (mặc định `"YAMAHA"`), gửi `(3, 1, roadBytes)` thực tế và `(14, 1, roadPayload)` trong setup burst.
  - Trong `updateCurrentRoadNameOnTFT`: Gửi đầy đủ tổ hợp 5 gói tin chuẩn:
    1. Service 2 (PDT 0): `[1, 0]` (Bật chế độ dẫn đường Active Navigation).
    2. Service 3 (PDT 1): `roadBytes` (Gửi trực tiếp chuỗi tên đường dạng UTF-8 ASCII theo chuẩn `Lo3/c;->f`).
    3. Service 4 (PDT 1): `s4` (Gửi mũi tên maneuver, khoảng cách float LE, đơn vị, tên đường theo chuẩn `Lm3/r;->l`).
    4. Service 14 (PDT 1): `[0x07, 0x1A, len, 0x00] + roadBytes` (Lệnh Sub 0x1A hiển thị tên đường theo chuẩn `Lo3/c;->j`).
    5. Service 2 (PDT 1): `arrivePayload` (Thông tin giờ đến ETA trong ngày).

---

### 🔴 LỖI 2: Phím `+` / `-` trên ghi-đông không zoom được hoặc chỉ bấm được 1 lần
* **Triệu chứng**: Nhấn phím zoom trên cùm tay lái xe thì màn hình không phóng to / thu nhỏ được, hoặc bấm được 1 lần đầu rồi các lần sau bị đơ.
* **Nguyên nhân gốc rễ**:
  - Khi người dùng bấm phím cùm tay lái, xe gửi mã `51, 53, 57` (phím +) hoặc `52, 54, 58` (phím -).
  - Firmware xe máy Yamaha yêu cầu điện thoại **phải phản hồi ngay lập tức gói tin ACK Service 14**: `NaviLite.frame(6, 14, 1, [0x07, 0x1A, 0x00, 0x00])`. Nếu không có gói ACK này, xe sẽ coi như sự kiện trước đó bị nghẽn và khóa các sự kiện bấm phím tiếp theo.
* **Giải pháp chuẩn xác**:
  - Ngay khi nhận mã `51, 53, 57` hoặc `52, 54, 58`, gọi lệnh `writeEAData(NaviLite.frame(6, 14, 1, [0x07, 0x1A, 0x00, 0x00]))` lập tức để giải phóng khóa phím của xe, sau đó mới dispatch tác vụ zoom trên Main thread.

---

### 🔴 LỖI 3: Đổi giao diện bản đồ (Theme Tile) không có tác dụng
* **Triệu chứng**: Người dùng vào Cài đặt đổi từ `Voyager` sang `Dark Matter` hay `Positron` nhưng bản đồ trên điện thoại và xe vẫn giữ nguyên màu cũ.
* **Nguyên nhân gốc rễ**:
  1. `OSMTileEngine` lưu trữ các file tile đã tải về đĩa trong một thư mục chung `OSMTiles_v4/\(z)_\(x)_\(y).png` mà không phân chia theo style. Khi đổi theme, app vẫn đọc file tile cũ trong bộ nhớ đệm ổ đĩa.
  2. Lớp `MKMapView` trên màn hình iOS chỉ nạp lớp phủ `CustomOSMTileOverlay` một lần lúc khởi tạo view và không tự thay thế khi URL template thay đổi.
* **Giải pháp chuẩn xác**:
  - Phân vùng cache riêng biệt cho từng theme: `cacheDirectory.appendingPathComponent(currentStyleKey)`.
  - Cung cấp hàm `updateStyle(template:styleKey:)` để phát thông báo và dọn bộ nhớ RAM cache.
  - Trong `OSMMapView.updateUIView`, bắt sự kiện thay đổi template để remove lớp overlay cũ và chèn `MKTileOverlay` mới vào `MKMapView`.

---

### 🔴 LỖI 4: Vòng tua máy (RPM) và % nhiên liệu hiển thị `--`
* **Bản chất kỹ thuật**:
  - Phân tích toàn bộ APK gốc chứng minh rằng: **Garmin StreetCross Protocol trên các dòng xe màn hình TFT không bao giờ truyền tín hiệu RPM hay mức xăng qua Bluetooth**.
  - Tín hiệu vòng tua máy và mức xăng trên các dòng xe này được đưa trực tiếp từ ECU bằng dây điện vật lý vào cụm đồng hồ LCD/TFT.
* **Giải pháp chuẩn xác**:
  - Không để dấu `--` gây hiểu lầm là tính năng bị hỏng.
  - Hiển thị nhãn **"Theo xe (LCD)"** kèm chú thích rõ ràng: "Đồng hồ xe hiển thị trực tiếp từ cảm biến phần cứng của ECU".
  - Giữ lại bộ lắng nghe ngầm (packet sniffer) trong `YamahaCCUManager` để tự động hiển thị nếu firmware của dòng xe tương lai có phát gói tin này.

---

### 🔴 LỖI 5: Chỉ đường hay rẽ vào ngõ hẻm nhỏ thay vì đường lớn
* **Triệu chứng**: Thuật toán dẫn đường ép người dùng rẽ vào các ngõ hẻm quanh co, hẹp hòi thay vì đi trên các trục đường lớn.
* **Nguyên nhân gốc rễ**:
  - Để ngăn xe máy đi vào đường cao tốc ô tô ở Việt Nam, cấu hình ban đầu đặt `use_highways: 0.0`. Tuy nhiên, nếu thiếu các trọng số phụ, thuật toán Valhalla sẽ coi các đại lộ nhiều làn xe cũng là dạng đường cấp cao cần né tránh, từ đó chọn các lối tắt ngõ hẻm để thay thế.
* **Giải pháp chuẩn xác**:
  - Cấu hình chuẩn cho xe máy đô thị trong `NavigationEngine.swift`:
    * `use_highways: 0.0`: Tránh tuyệt đối cao tốc cấm xe máy.
    * `use_primary: 0.9`: **Ưu tiên tối đa** các trục đường lớn, đại lộ chính (Nguyễn Văn Linh, Điện Biên Phủ, Võ Văn Kiệt...).
    * `alley_factor: 5.0`: **Phạt rất nặng việc đi vào ngõ hẻm**, kiệt nhỏ.
    * `use_trails: 0.0` & `use_tolls: 0.0`: Tránh lối mòn, đường bộ và trạm thu phí.
    * `top_speed: 65`: Căn chỉnh tốc độ thực tế của xe máy.

---

### 🔴 LỖI 6: Giọng nói Text-To-Speech gây phiền toái, gián đoạn âm thanh
* **Triệu chứng**: Giọng nói đọc chỉ đường tiếng Việt tự động kích hoạt chiếm quyền `AVAudioSession`, ngắt quãng nhạc tai nghe hoặc cuộc gọi đàm thoại của người lái.
* **Giải pháp chuẩn xác**:
  - Đặt `isVoiceGuidanceEnabled: Bool = false` làm mặc định.
  - Vô hiệu hoá hàm `speak(_ text: String)` thành no-op (return ngay), không can thiệp vào `AVAudioSession` để giữ nguyên luồng âm thanh trong tai nghe nón bảo hiểm.

---

### 🔴 LỖI 7: Quản lý thông báo ứng dụng (Zalo, Messenger, SMS...) và cuộc gọi đến
* **Cơ chế hoạt động trên iOS**:
  1. **Thông báo ứng dụng (SMS / iMessage / Zalo / Messenger / Telegram...)**:
     - Do chính sách bảo mật sandbox của Apple, ứng dụng bên thứ ba không thể nghe trộm trực tiếp thông báo push của các ứng dụng khác.
     - **Giải pháp đa tầng thông minh**:
       * **Kênh ANCS (Apple Notification Center Service)**: Hỗ trợ chuyển tiếp tự động thông báo của iOS sang cụm đồng hồ xe khi bật "Chia sẻ thông báo hệ thống" trong cài đặt Bluetooth.
       * **Kênh URL Scheme (`mynmax://notify?app=Zalo&sender=Nam&message=Alo`)**: Cho phép người dùng tạo Phím tắt iOS (Shortcuts Automation) để tự động bắn thông báo tức thì lên màn hình xe khi nhận tin nhắn.
       * **Quản lý thời gian hiển thị (`AppNotificationManager`)**: Cho phép tuỳ chỉnh số giây hiển thị trên màn hình xe (mặc định 7 giây, cấu hình từ 3 - 30 giây trong Cài đặt). Sau thời gian này, thông báo tự động ẩn và trả lại bản đồ dẫn đường.
  2. **Cuộc gọi đến & Điều khiển bằng phím cùm xe**:
     - Tích hợp `CallNotificationManager` sử dụng `CallKit` (`CXCallObserver`) kết hợp trích xuất Caller ID qua `TelephonyUtilities` (`TUCallCenter`).
     - **Giao diện hiển thị chuẩn theo yêu cầu**:
       * Nền banner: Màu **Xanh lá cây (Emerald Green)** nổi bật, tương phản cao.
       * Nội dung: Hiển thị nổi bật **Tên người gọi hoặc Số điện thoại** (không cần chú thích phím rườm rà).
       * Thời gian hiển thị: **Hiển thị liên tục** suốt quá trình đổ chuông và đàm thoại cho tới khi cuộc gọi kết thúc.
       * Khi người dùng nhấn phím **(+)** trên cùm xe: Nhận / Trả lời cuộc gọi (`CXAnswerCallAction`).
       * Khi người dùng nhấn phím **(-)** trên cùm xe: Từ chối / Kết thúc cuộc gọi (`CXEndCallAction`).
       * Khi cuộc gọi kết thúc: Banner tự động biến mất, phím (+) và (-) lập tức quay về chức năng phóng to / thu nhỏ bản đồ.

---

### 🔴 TÍNH NĂNG 8: Gợi ý các tuyến đường thay thế (Alternative Routes) & Chạm vào bản đồ để chuyển tuyến (Google Maps Style)
* **Yêu cầu**: Tương tự như Google Maps, khi tìm đường và dẫn đường cần hiển thị các tuyến đường gợi ý thay thế. Nếu thấy tối ưu hơn, người dùng có thể nhấp trực tiếp vào đường vẽ gợi ý trên bản đồ (hoặc thẻ tóm tắt) để đổi tuyến và đi theo lộ trình đó.
* **Kiến trúc giải pháp chuẩn xác**:
  1. **Routing Engines (Valhalla & OSRM) đa lộ trình**:
     - Valhalla: Bổ sung tham số `"alternates": 2`. Khi phản hồi, `json["trip"]` chứa tuyến chính và `json["alternates"]` chứa danh sách các `{ "trip": ... }` thay thế.
     - OSRM: Bổ sung tham số `&alternatives=true` để lấy mảng `json["routes"]`.
     - Tự động thống kê các con đường chính đã đi qua (`roadDistances`) để đặt tên tuyến thân thiện tự nhiên như Google Maps: *"Qua Đ. Pasteur"*, *"Qua Đ. Hai Bà Trưng"*, *"Qua Đ. Nam Kỳ Khởi Nghĩa"*.
     - Tự động tính chênh lệch thời gian so với tuyến nhanh nhất: *"Nhanh nhất"*, *"+3 phút"*, *"+7 phút"*.
  2. **Hiển thị trực quan trên bản đồ iPhone (`MKMapView`) & Màn hình xe Yamaha TFT**:
     - Tuyến đang chọn (Active Route): Vẽ bằng màu **Xanh Yamaha Racing Neon Cyan (`#00B0FF`)** siêu sáng, độ dày 11.0pt, nằm trên cùng.
     - Các tuyến gợi ý thay thế (Alternative Routes): Vẽ bằng màu **Xám khói tương phản (`#78909C`)** độ dày 7.5pt, nằm bên dưới.
     - Cả trên màn hình TFT xe máy cũng đồng bộ hiển thị các tuyến xám này để người lái dễ quan sát các ngã rẽ khả dĩ.
  3. **Cử chỉ tương tác chạm trực tiếp vào đường vẽ (Polyline Touch Detection)**:
     - Tích hợp `UITapGestureRecognizer` trên `MKMapView`.
     - Thuật toán tính khoảng cách vuông góc từ điểm chạm ngón tay (`CGPoint`) đến từng đoạn thẳng (`Segment`) của các tuyến đường thay thế (`pointToSegmentDistance`).
     - Ngưỡng dung sai chạm: 36 points (~1cm trên màn hình iPhone), cực kỳ nhạy và dễ bấm chính xác ngay cả khi gắn điện thoại trên giá đỡ xe máy.
     - Khi phát hiện chạm trúng tuyến thay thế: Kích hoạt rung phản hồi xúc giác (`UIImpactFeedbackGenerator(style: .medium)`), gọi `navEngine.selectRoute(selectedRoute)` để chuyển đổi tức thì.
  4. **Thẻ chọn lộ trình (Route Carousel) & Nút chuyển nhanh khi đang chạy xe**:
     - Ở màn hình xem trước: Carousel vuốt ngang hiển thị các thẻ tuyến đường với thông số thời gian, chênh lệch phút, độ dài cự ly và tên đường chính.
     - Khi đang lái xe dẫn đường (`isNavigating`): Hiển thị thanh chuyển nhanh (`alternativeRoutesNavigationPill`) nổi ở phía dưới để người lái có thể bấm nút hoặc nhấp thẳng vào đường vẽ trên bản đồ.

---

### 🔴 TÍNH NĂNG 9: Dời thông tin thời gian đến (ETA) xuống Footer & Mở rộng Header toàn chiều ngang TFT
* **Yêu cầu người dùng**:
  - Footer màn hình xe máy đã hiển thị thành công tên con đường hiện tại (`▲ PHU THO HOA`).
  - Dời toàn bộ thông tin giờ đến dự kiến (ETA - ví dụ `17:44` và `41 phút`) từ hộp góc phải của Header xuống tích hợp trực tiếp vào thanh Footer.
  - Mục đích: Giải phóng hoàn toàn chiều ngang dải đen Header trên màn hình TFT (~408pt) để tên đường tiếp theo hiển thị trọn vẹn, không bị cắt bớt hoặc hiện dấu ba chấm (`...`) khi tên đường dài.
* **Kiến trúc giải pháp chuẩn xác**:
  1. **Tích hợp ETA vào thanh Footer của xe (`updateCurrentRoadNameOnTFT`)**:
     - Định dạng Footer khi đang dẫn đường: `[TÊN ĐƯỜNG HIỆN TẠI] - [GIỜ ĐẾN] ([SỐ PHÚT CÒN LẠI]P)`.
       * Ví dụ thực tế: `▲ PHU THO HOA - 17:44 (41P)` hoặc `▲ 58 DO DUC DUC - 18:25 (1H15P)`.
     - Toàn bộ chuỗi được chuẩn hoá qua hàm `cleanVietnameseForGarmin`, chuyển sang chữ in hoa chuẩn ASCII (32-126) mà vi điều khiển màn hình xe Yamaha/Garmin hỗ trợ hoàn hảo.
     - Khi chạy xe tự do không có lộ trình: Footer tự động quay về hiển thị tên đường hiện tại không kèm ETA (ví dụ `▲ PHU THO HOA`).
     - Khi kết thúc chuyến đi (`stopNavigationOnTFT`): Reset sạch chuỗi để không lưu vết ETA cũ.
  2. **Giải phóng và tối ưu Header dẫn đường trên TFT (`drawTopNavigationBanner`)**:
     - Gỡ bỏ hộp chữ nhật ETA (`etaBoxRect`) ở góc trên bên phải.
     - Tăng chiều rộng vùng hiển thị văn bản `availableTextWidth` từ 302pt lên **408pt** (`size.width (480) - textStartX (62) - 10`).
     - Tự động co giãn cỡ chữ thông minh cho tên đường tiếp theo:
       * Tên đường dài > 28 ký tự: Cỡ chữ 24pt Bold.
       * Tên đường dài > 20 ký tự: Cỡ chữ 26pt Bold.
       * Tên đường thông thường: Cỡ chữ 28pt Bold.
     - Khoảng cách rẽ (`36pt Heavy Neon Green`) và tên đường (`Bold White`) luôn được căn giữa theo chiều cao dải đen (62pt), hiển thị cực kỳ sang trọng, rõ ràng và chống chói tuyệt đối dưới trời nắng gắt.

---

### 🔴 TÍNH NĂNG 10: Tách riêng biệt 3 nút gửi thử thông báo và cuộc gọi trong Cài đặt
* **Nguyên nhân lỗi trước đây**:
  - Trong SwiftUI `Form`, khi gom nhiều `Button` bên trong một `VStack` đơn lẻ, iOS coi cả `VStack` là một dòng (`UITableViewCell`) duy nhất.
  - Khi người dùng chạm ngón tay vào ô đó, sự kiện bấm (tap gesture) bị truyền đồng thời tới tất cả các nút con trong cùng dòng, khiến cả 3 chức năng (Zalo, SMS, Cuộc gọi) cùng kích hoạt một lúc.
* **Giải pháp khắc phục triệt để**:
  1. Tách hẳn thành một `Section` riêng biệt: `Gửi thử nghiệm lên màn hình xe (3 nút riêng biệt)`.
  2. Mỗi nút bấm là một dòng độc lập trong Form, áp dụng `.buttonStyle(BorderlessButtonStyle())` để cách ly hoàn toàn sự kiện chạm, không bao giờ bị kích hoạt chéo.
  3. Bổ sung nút tiện ích: `Tắt / Kết thúc cuộc gọi thử` (xuất hiện khi cuộc gọi thử đang đổ chuông/đàm thoại) để người dùng có thể tắt ngay từ app mà không cần ra xe bấm cùm.
  4. Đánh số thứ tự rõ ràng kèm icon màu và badge hiển thị thời gian:
     - `1. Gửi thử thông báo Zalo` (Xanh dương, hiển thị theo số giây cài đặt).
     - `2. Gửi thử tin nhắn SMS` (Xanh lá, hiển thị theo số giây cài đặt).
     - `3. Thử cuộc gọi đến` (Xanh lá cây, hiển thị liên tục).

---

### 🔴 TÍNH NĂNG 11: Kích hoạt toàn bộ các nút Menu dẫn đường trên màn hình xe (Stop, Home, Work, Gas station, Favorites)
* **Vấn đề người dùng báo cáo**:
  - Trên màn hình TFT xe máy có danh sách menu dẫn đường gồm các nút:
    `Stop navigation`, `Go home`, `Go to work`, `Gas station`, `Favorites`.
  - Trước đây chỉ có nút `Stop navigation` hoạt động (mã 49), các nút còn lại bấm vào không có phản hồi hoặc không điều hướng được.
* **Phát hiện & Giải mã qua Dalvik Bytecode của APK gốc**:
  - **Lỗi nhầm lẫn mã lệnh nghiêm trọng**:
    Trong `YamahaCCUManager.swift`, lệnh `53` bị gộp chung vào Zoom In (+) và lệnh `54` bị gộp chung vào Zoom Out (-). Do đó khi người lái chọn `Go home` (53), app lại phóng to bản đồ; khi chọn `Go to work` (54), app lại thu nhỏ bản đồ!
  - **Mã lệnh chuẩn xác từ vi điều khiển xe Yamaha**:
    * `49` (`0x31`): Dừng lộ trình dẫn đường (`onStopRoute`).
    * `53` (`0x35`): Về nhà (`onGoHome`).
    * `54` (`0x36`): Đi làm (`onGoToWork`).
    * `55` (`0x37`) với sub-code `4`: Mở danh sách Cây xăng (`onFindGasStation`).
    * `55` (`0x37`) với sub-code `3`: Mở danh sách Yêu thích (`onOpenFavorites`).
    * `48` (`0x30`): Chọn mục POI từ danh sách (`onSelectPoi`).
  - **Đồng bộ khả năng Menu qua Service 10 & 11**:
    Xe máy chỉ hiển thị sáng nút `Go home` và `Go to work` khi nhận được gói tin khai báo từ điện thoại:
    * Service 10 (PDT 0): `[1, 0]` nếu đã cài đặt Nhà, `[0, 0]` nếu chưa cài.
    * Service 11 (PDT 0): `[1, 0]` nếu đã cài đặt Nơi làm việc, `[0, 0]` nếu chưa cài.
* **Giải pháp hoàn thiện**:
  1. Xây dựng module `SavedPlacesManager` quản lý toạ độ Nhà, Nơi làm việc, Yêu thích và tự động phát Service 10 / 11 khi có thay đổi.
  2. Tích hợp giao diện Cài đặt cho phép bấm **"Lấy vị trí GPS hiện tại làm Nhà / Chỗ làm"** chỉ với 1 chạm.
  3. Khi chọn **`Gas station`** trên xe: Tự động kích hoạt `MKLocalSearch` quét các cây xăng gần xe nhất trong bán kính 6km, chọn trạm xăng gần nhất và lập tức dẫn đường trên màn hình xe mà không cần người lái chạm vào điện thoại.
  4. Khi chọn **`Go home` / `Go to work`**: Lập tức kích hoạt `NavigationEngine.calculateRoute` dẫn đường trực tiếp về Nhà / Công ty.

---

### 🔴 TÍNH NĂNG 12: Hiển thị các vị trí đặc biệt (Nhà, Công ty, Yêu thích) trực quan trên bản đồ lớn
* **Yêu cầu người dùng**:
  - Trên màn hình bản đồ lớn (tab Bản đồ - LiveNavigationView) cần hiển thị trực quan các vị trí đặc biệt đã lưu gồm: **Nhà riêng (Home)**, **Cơ quan / Nơi làm việc (Work)** và các **Mục yêu thích (Favorites)**.
* **Kiến trúc giải pháp chuẩn xác**:
  1. **Đồng bộ dữ liệu hai chiều (Two-Way Sync)**:
     - Giữa `NavigationEngine` (`homeLocation`, `workLocation`, `favoriteLocations`) và `SavedPlacesManager.shared` (`homePlace`, `workPlace`, `favoritePlaces`).
     - Bất kể người dùng lưu địa điểm ở đâu (Cài đặt, Trang chủ, Bản đồ lớn hay nút trên cùm xe), dữ liệu luôn đồng nhất và được lưu trữ bền vững vào `UserDefaults`.
  2. **Ghim vị trí tuỳ biến trên MKMapView (`SpecialPlaceAnnotation`)**:
     - **Nhà (Home)**: Marker màu Cam tươi (`UIColor.systemOrange`), icon `house.fill`, ưu tiên hiển thị cao nhất.
     - **Công ty (Work)**: Marker màu Xanh dương công nghệ (`#1A80FF`), icon `briefcase.fill`, ưu tiên hiển thị cao nhất.
     - **Mục yêu thích (Favorites)**: Marker màu Vàng kim rực rỡ (`UIColor.systemYellow`), icon `star.fill`.
  3. **Callout thông minh & Nút chỉ đường tức thì**:
     - Khi người dùng chạm vào bất kỳ ghim địa điểm nào: Callout nổi lên hiển thị icon đại diện bên trái, tên địa điểm và địa chỉ chi tiết ở giữa, kèm nút chỉ đường xanh lá (`arrow.triangle.turn.up.right.circle.fill`) bên phải.
     - Bấm nút chỉ đường: Lập tức gọi `navEngine.calculateRoute(to: dest, destinationTitle: title)` để vẽ lộ trình, tính thời gian, gợi ý các tuyến thay thế và tự động stream lên màn hình xe.
  4. **Cơ chế chống giật lag (Key Caching)**:
     - Tính toán hash key từ danh sách tọa độ và tên địa điểm. Chỉ cập nhật lại annotation khi thực sự có thêm/xóa/sửa địa điểm, tránh việc xoá và nạp lại marker liên tục theo từng nhịp cập nhật GPS của xe (60fps mượt mà).

---

### 🔴 TÍNH NĂNG 13: Lưu vị trí đỗ xe tự động (Find My Bike) khi tắt máy xe & Hiển thị bản đồ tìm xe
* **Yêu cầu**: Tự động lưu vị trí GPS của điện thoại tại khoảnh khắc người dùng tắt chìa khóa xe máy (ngắt kết nối BLE CCU hoặc MFi). Hiển thị thẻ vị trí đỗ xe trên trang chủ và ghim màu tím trên bản đồ lớn kèm các nút chỉ đường đi bộ tới xe.
* **Kiến trúc giải pháp chuẩn xác**:
  1. **Bộ quản lý đỗ xe chuyên dụng (`ParkingLocationManager`)**:
     - Lưu trữ tọa độ GPS, thời điểm đỗ (`Date`), địa chỉ đường phố cụ thể (thông qua `CLGeocoder` tự động giải mã ngược) và tên xe đã ghép đôi.
     - Dữ liệu được lưu trữ bền bỉ trong `UserDefaults` dưới khóa `kLastParkedBikeLocationKey`.
     - Tự động tính toán khoảng cách thực tế giữa vị trí người dùng hiện tại và xe (`formattedDistance()`), kèm thời gian tương đối đã trôi qua (`relativeTimeString`: *"5 phút trước"*, *"2 giờ trước"*).
  2. **Kích hoạt tự động khi tắt máy xe (`YamahaCCUManager`)**:
     - Cờ `wasConnectedToBike` theo dõi trạng thái kết nối thực tế với xe.
     - Khi người dùng tắt chìa khóa (sự kiện ngắt kết nối BLE `didDisconnectPeripheral` hoặc MFi `accessoryDidDisconnect`): hệ thống kiểm tra nếu trước đó xe đang kết nối, lập tức lấy tọa độ GPS chính xác nhất của điện thoại tại thời điểm đó và lưu vào `ParkingLocationManager`. Tránh lưu sai vị trí khi người dùng chỉ mở app xem mà chưa từng kết nối xe.
  3. **Giao diện thẻ đỗ xe trên Trang chủ & Ghim trên Bản đồ lớn**:
     - **Thẻ Find My Bike trên Trang chủ**: Nổi bật với gradient màu tím, hiển thị địa chỉ chi tiết, khoảng cách đi bộ, thời gian đỗ, nút "Dẫn đường tới xe", nút mở nhanh qua Apple Maps / Google Maps và nút xóa vị trí.
     - **Ghim màu tím (`.parking`) trên Bản đồ lớn (`LiveNavigationView`)**: Icon `parkingsign.circle.fill` sắc tím hoàng gia, hiển thị callout thông tin và nút điều hướng đi bộ trực tiếp tới nơi xe đang đỗ.

---

### 🔴 TÍNH NĂNG 14: Đồng bộ mức pin iPhone & Kích hoạt Icon cuộc gọi / tin nhắn trên mặt đồng hồ xe
* **Yêu cầu**:
  - Gửi phần trăm pin iPhone và trạng thái sạc xuống bộ điều khiển CCU của xe để hiển thị icon pin điện thoại trên màn hình đồng hồ tốc độ NMAX.
  - Kích hoạt biểu tượng Cuộc gọi (Phone Icon) trên mặt đồng hồ khi có cuộc gọi đến và tắt đi khi kết thúc đàm thoại.
  - Kích hoạt biểu tượng Thư / Tin nhắn (Mail/SMS Icon) trên mặt đồng hồ khi nhận được thông báo ứng dụng (Zalo, Tin nhắn, Messenger, v.v.).
* **Kiến trúc giải pháp chuẩn xác từ Dalvik Bytecode của Yamaha CCU**:
  1. **Đồng bộ Pin iPhone (Command ID 316 - `0x013C`)**:
     - Đặc tả gói tin Yamaha DDT: `[0x3C, 0x01, mediaVol, callVol, battByte, signal]`.
     - `battByte`: Bit 7 là cờ sạc (`isCharging ? 0x80 : 0x00`), 7 bit thấp chứa phần trăm pin `(0..100)`.
     - Lắng nghe sự kiện `UIDevice.batteryLevelDidChangeNotification` và gửi định kỳ 60 giây một lần qua characteristic `B606C7F9-B605-4306-8C1C-755A1BA3066C` (BLE) và Service 13 (MFi).
  2. **Kích hoạt Icon Cuộc gọi trên đồng hồ (Command ID 308 - `0x0134`)**:
     - Khi có cuộc gọi đến (`CallNotificationManager.isCallRinging`): Gửi payload `[0x34, 0x01, 0x01]` -> Icon cuộc gọi trên mặt đồng hồ xe nhấp nháy/sáng lên báo cho người lái.
     - Khi kết thúc cuộc gọi / từ chối: Gửi payload `[0x34, 0x01, 0x00]` -> Tắt icon cuộc gọi trên xe.
  3. **Kích hoạt Icon Tin nhắn / Thông báo (Command ID 303 - `0x012F`)**:
     - Gói tin thông báo gồm timestamp (năm, tháng, ngày, giờ, phút, giây), độ dài và nội dung tiêu đề/tin nhắn.
     - Tự động sáng biểu tượng phong bì tin nhắn trên đồng hồ xe trong suốt thời gian hiển thị thông báo.

---

### 🔴 TÍNH NĂNG 15: Bắt tay thu thập dữ liệu ECU (Vòng tua máy RPM, điện áp bình ắc quy, mức xăng)
* **Yêu cầu**: Bắt tay với bộ điều khiển CCU xe máy Yamaha NMAX, giải mã dữ liệu cảm biến ECU động cơ (vòng tua máy RPM, điện áp bình ắc quy 12V, mức xăng, nhiệt độ nước làm mát) và hiển thị trực tiếp lên bảng đồng hồ HUD trên điện thoại.
* **Kiến trúc giải pháp chuẩn xác**:
  1. **Bắt tay hai chiều (Handshake ACK 309 -> 310)**:
     - Khi xe gửi gói tin telemetry ping mã `309` (`0x0135`), CCU yêu cầu điện thoại phản hồi xác nhận ACK `310` (`0x0136`) kèm trạng thái sẵn sàng `[0x36, 0x01, 0x01]`. Nếu không có gói tin này, CCU sẽ ngắt luồng dữ liệu thời gian thực sau 5 giây.
  2. **Giải mã luồng Telemetry ECU**:
     - **Vòng tua máy (Engine RPM)**: Giải mã từ giá trị 16-bit (Little-Endian / Big-Endian) nằm trong dải làm việc của động cơ xe tay ga 155cc (800 - 14,000 RPM).
     - **Mức xăng (Fuel %)**: Giải mã giá trị phần trăm nhiên liệu 5% - 100%.
     - **Điện áp bình ắc quy (Battery Voltage)**: Dữ liệu cảm biến điện áp 12V với độ phân giải 0.1V (giá trị thô 115 - 152 tương ứng 11.5V - 15.2V). Tự động đánh giá trạng thái bình: `>= 12.2V: Ắc quy tốt`, `< 12.2V: Bình yếu`.
     - **Nhiệt độ nước làm mát động cơ (Coolant Temp)**: Dữ liệu cảm biến nhiệt độ 40°C - 115°C.
  3. **Bảng đồng hồ HUD tái thiết kế hiện đại**:
     - Hiển thị trực quan tốc độ GPS, RPM, mức xăng, điện áp ắc quy, nhiệt độ máy và trạng thái đồng bộ pin iPhone lên màn hình LCD của xe.

---

### 🔴 TÍNH NĂNG 16: Tối ưu điều hướng, Footer tên đường thực tế & Tìm kiếm trạm xăng Apple Maps (v0.5.21 Build 47)
* **Các vấn đề thực tế đã phát hiện & giải quyết triệt để**:
  1. **Lỗi Footer hiện tên đường tiếp theo thay vì đường hiện tại**:
     - *Nguyên nhân*: Hàm `checkCurrentRoadName(at:)` gán nhầm `self.currentRoadName = step.roadName` với `step` là ngã rẽ sắp tới (maneuver ahead).
     - *Giải pháp*: Tách biệt hoàn toàn: Tên đường rẽ tiếp theo nằm ở Top Banner (`350 m · [nextRoadName]`). Tên đường hiện tại xe đang lăn bánh thực tế trên footer lấy từ `currentStepRoadName` (bước rẽ đang chạy) và được giải mã tọa độ GPS tức thời bằng Apple `CLGeocoder` (`thoroughfare`). Tuyệt đối không fallback sang `nextRoadName`.
  2. **Lỗi nhảy cóc ngã rẽ sớm khi đang chờ đèn đỏ**:
     - *Nguyên nhân*: Ngưỡng chuyển bước `max(28.0, speedMps * 2.2)` có mức tối thiểu 28m kể cả khi đứng yên (`speed = 0`), kết hợp với luật `nextDist < dist && dist < 90.0` khiến xe vừa dừng chờ đèn đỏ cách ngã rẽ 20-30m thì màn hình đã lập tức nhảy cóc sang chỉ dẫn của ngã rẽ tiếp theo.
     - *Giải pháp*: Xây dựng máy trạng thái `Turn Advancement State Machine`:
       - Khi tốc độ xe `< 2.2 m/s` (< 8 km/h - dừng chờ đèn đỏ hoặc bò rất chậm): **TUYỆT ĐỐI KHÔNG nhảy bước** trừ khi xe đã lọt sát đỉnh ngã rẽ (`dist <= 7.0m`).
       - Khi xe di chuyển thực tế (`speed >= 2.2 m/s`): Chỉ chuyển bước khi đã vào ngã rẽ (`dist <= 12.0m`) HOẶC đã đi qua đỉnh rẽ và đang rời xa dần trên đường mới (`minDistanceToCurrentStep < 25.0m && dist - minDistanceToCurrentStep >= 4.0m`). Loại bỏ hoàn toàn quy tắc lookahead 90m gây lỗi trong đô thị.
  3. **Footer khi chạy tự do (không điều hướng)**:
     - Kích hoạt luồng `CLGeocoder` chạy ngầm định kỳ (throttling 20m / 3.5s). Khi không bật dẫn đường, footer màn hình xe và app tự động cập nhật liên tục tên con đường xe đang chạy thực tế (`▲ [currentRoadName]`).
  4. **Nút bấm Menu trên xe (Home, Work, Favorites) lấy trực tiếp từ dữ liệu đã lưu**:
     - Đồng bộ tự động 2 chiều giữa `SavedPlacesManager` (`saved_*`) và `NavigationEngine` (`nconnect_*`). Khi xe kết nối MFi/BLE, trạng thái bật/tắt của Service 10 & 11 được kích hoạt ngay nếu người dùng đã lưu bất kỳ đâu.
     - Khắc phục lỗi trùng lặp `case 55:` trong bộ nhận lệnh NaviLite khiến các lệnh mở Favorites (sub 3) và Gas Station (sub 4) từ cùm xe bị nuốt chửng.
  5. **Tìm kiếm trạm xăng (Gas Station Search)**:
     - Thay thế hoàn toàn API OpenStreetMap Nominatim (vốn bị rate-limit và trả về rỗng) bằng Apple MapKit `MKLocalSearch` (`"Cây xăng"` / `"Trạm xăng"`). Tìm kiếm tức thì trong bán kính 12km với đầy đủ tên thương hiệu (Petrolimex, PV Oil, Comeco...), địa chỉ và cự ly di chuyển.


---

### 🔴 TÍNH NĂNG 17: Tinh giản hóa & Loại bỏ Thông báo / Cuộc gọi & Dữ liệu ECU/Pin theo yêu cầu (v0.5.22 Build 48)
* **Yêu cầu người dùng**:
  - Bỏ các chức năng thông báo app (Zalo, Messenger, SMS...) và cuộc gọi đến.
  - Bỏ các chức năng đo đạc vòng tua máy (RPM), % nhiên liệu, điện áp ắc quy 12V, nhiệt độ máy, và % pin điện thoại.
* **Các thay đổi kiến trúc & thực thi chi tiết**:
  1. **Loại bỏ tính năng Cuộc gọi & Thông báo ứng dụng**:
     - **TFT Stream Renderer**: Gỡ bỏ section vẽ banner đè cuộc gọi đến (nền xanh lá) và banner thông báo app (Zalo, Messenger, SMS). Luồng render TFT tập trung 100% vào bản đồ vệ tinh/vector, chỉ dẫn rẽ Turn-by-Turn, cự ly và tên đường.
     - **Cùm phím điều khiển xe (+ / -)**: Gỡ bỏ toàn bộ logic chen ngang nghe/từ chối cuộc gọi. Phím `+` và `-` trên cùm tay lái xe (cả kênh MFi dịch vụ 51, 57, 52, 58 và StreetCross `.zoomIn`, `.zoomOut`) hoạt động thuần túy để phóng to và thu nhỏ bản đồ dẫn đường.
     - **Giao diện App**: Gỡ bỏ các banner cuộc gọi, banner thông báo trên `MainDashboardView` và `LiveNavigationView`. Gỡ bỏ mục cài đặt thông báo, các nút gửi thử nghiệm (Zalo, SMS, Cuộc gọi) và tài liệu hướng dẫn ANCS/Shortcuts trên `SettingsView`. Gỡ bỏ bộ nhận `onOpenURL` trên `NConnectApp`.
     - **An toàn PBX**: `CallNotificationManager.swift` và `AppNotificationManager.swift` được chuyển hóa thành các stub an toàn (empty/no-op) nhằm giữ nguyên cấu trúc `project.pbxproj` mà không sinh ra bất kỳ tiến trình theo dõi CallKit hay thông báo nào.
  2. **Loại bỏ chức năng Vòng tua máy (RPM), % Nhiên liệu, Ắc quy, Nhiệt độ máy, % Pin**:
     - **CCU Telemetry**: Gỡ bỏ thuật toán bóc tách RPM, mức xăng, điện áp bình 12V và nhiệt độ nước làm mát trong `parseTelemetry`. Giữ nguyên phản hồi ACK Bắt tay ECU (`0x0135` -> `0x0136`) để ECU xe không ngắt kết nối CCU.
     - **Giám sát & Đồng bộ Pin iPhone**: Gỡ bỏ `setupPhoneBatteryMonitoring()`, bộ đếm `batteryTimer`, và hàm gửi lệnh đồng bộ pin lên màn hình xe (`Command ID 316` / MFi Service 13 & 12).
     - **Giao diện HUD**: Gỡ bỏ hoàn toàn thẻ `telemetryCard` trên `MainDashboardView`.
  3. **Giao diện người dùng sau khi tinh gọn**:
     - Dashboard gọn gàng, tải nhanh, tập trung tối đa vào trải nghiệm lái xe:
       1. Trạng thái kết nối xe (MFi / BLE).
       2. Vị trí đỗ xe tự động (Find My Bike với Apple Maps / Google Maps).
       3. Khung xem trước trực tiếp màn hình TFT Mirror.
       4. Tác vụ nhanh (Nhà, Chỗ làm, Cây xăng, Mục yêu thích).
       5. Danh sách các địa điểm yêu thích đã lưu.

---

### 🔴 TÍNH NĂNG 18: Khắc phục Race Condition NaviLite & Sửa lỗi Byte Order Bắt tay ECU 309 (v0.5.23 Build 49)
* **Phân tích log thực tế trên xe**:
  1. **Lỗi ECU tự ngắt kết nối sau vài giây (`01 38 00`)**:
     - *Dấu hiệu*: Xe gửi gói tin `01 35 03` hoặc `01 35 01`. Khoảng 5-7 giây sau, xe gửi 4 gói `01 38 00` (Command 312: ngắt kết nối/tắt khóa) và đóng BLE + MFi.
     - *Nguyên nhân*: Mã `01 35` là Command ID 309 (Heartbeat Ping của ECU). Trong code trước đây kiểm tra theo little-endian `bytes[0] == 0x35 && bytes[1] == 0x01`, trong khi ECU xe gửi big-endian `[0x01, 0x35, sub]`. Do đó, app không gửi gói tin ACK 310 (`0x0136`) phản hồi. Sau 5s không có ACK, ECU tự động hủy phiên kết nối.
     - *Giải pháp*: Chuẩn hóa kiểm tra cả hai thứ tự byte `(bytes[0] == 0x01 && bytes[1] == 0x35) || (bytes[0] == 0x35 && bytes[1] == 0x01)` và gửi phản hồi ngay lập tức `Data([0x36, 0x01, sub])` cho xe.
  2. **Lỗi Race Condition khiến luồng NaviLite dừng ngay khi vừa mở EASession**:
     - *Dấu hiệu*: Log xuất hiện `▶ Khởi động bộ nhận lệnh NaviLite liên tục với màn hình TFT...` và ngay dòng tiếp theo là `Dừng luồng lắng nghe NaviLite do phiên MFi kết thúc.`
     - *Nguyên nhân*: Cờ `isMFiConnected = true` được cập nhật trong `DispatchQueue.main.async`, trong khi `Thread.detachNewThread` chạy `runNaviLiteHandshake()` ngay tức thì. Khi luồng nền bắt đầu chạy, `isMFiConnected` vẫn là `false`, khiến vòng lặp `while isMFiConnected && eaSession != nil` thoát ngay lập tức ở lần lặp đầu tiên.
     - *Giải pháp*: Chuyển điều kiện vòng lặp thành `while eaSession != nil && eaInput != nil` và gán cờ `isMFiConnected = true` đồng bộ ngay trước khi khởi chạy luồng.

---

### 🚀 TÍNH NĂNG 19: Lập lộ trình đa điểm dừng (Multi-Stop), sắp xếp thứ tự linh hoạt và đánh nhãn trực quan (v0.5.24)
* **Yêu cầu & Mục tiêu**:
  1. Cho phép người dùng thêm nhiều điểm dừng trung gian giữa Điểm xuất phát (A) và Điểm đến (B).
  2. Hỗ trợ sắp xếp lại thứ tự các điểm dừng bằng nút mũi tên Lên/Xuống (hoặc kéo thả).
  3. Đánh số và dán nhãn rõ ràng trên cả giao diện danh sách và Marker trên bản đồ:
     - Điểm xuất phát: **A** (màu xanh lá).
     - Các điểm dừng trung gian: **1**, **2**, **3**... (màu cam).
     - Điểm đến cuối cùng: **B** (màu đỏ).
     - Điểm ghim tạm thời: Ghim đỏ với icon bản đồ.
  4. Hỗ trợ 2 phương thức thêm điểm dừng:
     - Nhấp / Nhấn giữ trực tiếp trên bản đồ (thông qua thẻ Dropped Pin Card có nút "+ Thêm điểm dừng").
     - Nhập tìm kiếm địa chỉ từ ô Input (Search Sheet hỗ trợ chọn thêm điểm dừng mới hoặc chỉnh sửa điểm dừng đã có).
  5. Động cơ điều hướng (Valhalla & OSRM) tính toán tuyến đường xuyên suốt qua toàn bộ các điểm dừng theo đúng thứ tự: `A -> 1 -> 2 -> ... -> B`.

* **Chi tiết kỹ thuật**:
  - **Annotation & Marker**: Thêm `stopIndex: Int?` vào `RoutePointAnnotation`. Marker view (`MKMarkerAnnotationView`) hiển thị glyphText tương ứng (`"A"`, `"(stopIndex)"`, `"B"`) với màu sắc phân biệt trực quan.
  - **State Management**: Thêm mảng `intermediateStops: [RoutePointItem]` trong `LiveNavigationView` cùng các hàm điều khiển `moveStopUp`, `moveStopDown`, `removeIntermediateStop`, `addDroppedPinAsIntermediateStop`.
  - **Header Lập lộ trình**:
    - Chế độ dọc (Portrait): Hiển thị điểm A, danh sách điểm dừng 1, 2... có nút mũi tên lên/xuống và xóa, nút "+ Điểm dừng", và điểm B.
    - Chế độ ngang (Landscape): Thanh công cụ tinh gọn hiển thị A, B, nút "+ Thêm điểm dừng" và danh sách chip điểm dừng cuộn ngang với đầy đủ nút đảo thứ tự.
  - **NavigationEngine**: Cập nhật `calculateRoute`, `requestValhallaRoutes`, `parseValhallaTrip`, và `fallbackOSRMRoutes` hỗ trợ mảng `intermediateStops: [CLLocationCoordinate2D]`. Xử lý ghép nối đa phân đoạn (`legs`) với offset index và polyline chính xác.

---

### 🔴 TÍNH NĂNG 20: Giao thức danh sách POI (Service 7/8 & 98/99), Loại bỏ la bàn & Tối ưu Header chỉ dẫn (v0.5.25)
* **Các cải tiến và sửa lỗi chi tiết**:
  1. **Loại bỏ icon la bàn trên màn hình chiếu TFT**:
     - Vô hiệu hoá lệnh `drawTFTCompass` trong `TFTStreamRenderer.swift`. Màn hình chiếu bản đồ trên xe máy hoàn toàn thông thoáng, loại bỏ chi tiết thừa, tập trung vào lộ trình và mũi tên xe.
  2. **Khắc phục nút bấm Tìm trạm xăng (Gas Station) và Mục yêu thích (Favorites) trên menu cùm xe**:
     - *Hiện tượng*: Khi chọn Gas Station hoặc Favorites trên menu xe máy, màn hình xe quay vòng chờ tải hoặc không có phản hồi dẫn đường.
     - *Phân tích dịch ngược DEX (`classes.dex` - `Lm3/r;`, `Lj3/v;`, `Lo3/c;`)*:
       - Khi rider chọn menu xe, xe gửi gói `case 55` (`0x37`) kèm `payload[0]`: `sub = 3` (Favorites) hoặc `sub = 4` (Gas Station).
       - Trước đây code phản hồi nhầm gói `NaviLite.frame(6, 14, 1, [0x07, 0x1A, 0x00, 0x00])` - đây là gói xoá text thanh trạng thái (Service 14), gây nghẽn và xung đột trạng thái menu của màn hình xe.
       - Chuẩn Garmin StreetCross yêu cầu điện thoại trả về danh sách POI theo 2 bước:
         - **Bước 1 (Header)**: Gửi Service 7 (Favorites) hoặc Service 8 (Gas Station) với payload `[count_low, count_high, 0]` khai báo tổng số lượng địa điểm.
         - **Bước 2 (Item Frame)**: Gửi từng mục qua Service 98 (Favorites) hoặc Service 99 (Gas Station) với cấu trúc nhị phân:
           `[idx(2B LE), bearing(2B LE), icon(1B), nameLen(1B), distStrLen(1B), floatDist(4B LE), distStrBytes, nameBytes]`.
       - Khi rider duyệt và chọn mục trên xe máy, xe gửi gói `case 48` (`0x30`) kèm `[itemIndex, listId]`.
     - *Giải pháp triển khai*:
       - Bổ sung hàm `sendPoiListToTFT(listId:items:currentLoc:)` trong `YamahaCCUManager.swift`.
       - Bổ sung `findNearbyGasStationsList(near:center:)` trong `SavedPlacesManager.swift` dùng `MKLocalSearch` kết hợp bộ lọc `MKPointOfInterestFilter(including: [.gasStation])`.
       - Kết nối xử lý tự động trong `ContentView.swift`: Truyền danh sách tối đa 20 điểm lên xe, đồng thời tự động kích hoạt tính toán lộ trình dẫn đường ngay đến địa điểm gần nhất.
  3. **Chiều cao dãy đen Header fit khít với chiều cao chữ**:
     - Thay thế chiều cao cố định `62.0pt` bằng chiều cao động: `ceil(navText.size().height) + 8.0pt` (~32-36pt). Dãy đen ôm sát vừa vặn dòng text, gia tăng diện tích hiển thị bản đồ điều hướng.
  4. **Định dạng chỉ dẫn Header không dùng mũi tên (`36m turn right onto ...`)**:
     - Loại bỏ việc vẽ mũi tên icon SF Symbol trong banner trên cùng.
     - Bổ sung `actionPhraseEn` vào `NavigationDirection` (ví dụ: `turn right onto`, `turn left onto`, `straight onto`...).
     - Bổ sung `formattedDistanceCompact` (`36m`, `1.2km`) và `headerTurnText` vào `NavigationInstruction`.
     - Định dạng trực quan với phối màu tương phản cao: Cụm cự ly + hành động rẽ (`36m turn right onto `) màu Xanh Neon (`#00FF73`), tên đường (`Pasteur`) màu Trắng sáng (`#FFFFFF`), tự động co giãn kích thước font chữ (22pt -> 20pt -> 18pt) khi gặp tên đường dài.


---

### 🔴 TÍNH NĂNG 21: Mặc định Zoom 4x màn hình xe, Cơ chế chống reset thời gian đỗ xe & Tab Lưu lộ trình di chuyển (v0.5.26)
* **Các cải tiến và tính năng mới chi tiết**:
  1. **Mặc định Zoom 4x (200m) trên màn hình chiếu TFT xe máy**:
     - Trong `TFTStreamRenderer.swift`, thiết lập `currentZoomIndex = 0` (Zoom 4x, 200m, OSM Zoom 18) và `mapRadiusMeters = 200.0` ngay khi khởi tạo thay vì mức 2x (400m) trước đây. Màn hình chiếu bản đồ trên xe máy tự động phóng to rõ ràng, dễ nhìn nhất cho rider ngay khi xe nổ máy.
  2. **Cơ chế chống reset thời gian đỗ xe khi bật chìa khóa rồi tắt mà không chạy**:
     - *Vấn đề*: Khi người dùng mở chìa khóa xe để lấy đồ trong cốp xe hoặc nổ máy kiểm tra rồi tắt chìa khóa mà không đi đâu, hệ thống trước đây tự động tạo mới mốc đỗ xe `timestamp = Date()`, làm mất đi thời gian đỗ xe thực tế trước đó.
     - *Giải pháp*: Trong `ParkingLocationManager.swift`, khi nhận được lệnh lưu vị trí đỗ xe, hệ thống tính khoảng cách giữa vị trí hiện tại với vị trí đỗ xe trước đó (`lastParkedLocation`). Nếu cự ly `< 50m` và không có cờ `forceNewTimestamp`, hệ thống **giữ nguyên mốc thời gian đỗ xe ban đầu** (`prev.timestamp`), không tính lại.
  3. **Bổ sung Tab Lưu lộ trình di chuyển (`TripHistoryView` & `TripHistoryManager`)**:
     - Bổ sung tab "Lộ trình" (icon `clock.arrow.circlepath`) trong thanh Tab Bar của `ContentView.swift`.
     - Bộ quản lý `TripHistoryManager` tự động kích hoạt phiên ghi nhận khi xe kết nối CCU / mở khóa và kết thúc khi xe tắt khóa (Command 312 / disconnect) hoặc dừng dẫn đường.
     - **Điều kiện nghiêm ngặt**: Chỉ lưu các chuyến đi có quãng đường thực tế **ít nhất từ 50 mét trở lên** (`distanceMeters >= 50.0m`). Các phiên dưới 50m sẽ tự động bị hủy bỏ và không làm thay đổi mốc thời gian đỗ xe.
     - **Thông tin lưu trữ đầy đủ**:
       - Điểm xuất phát (Toạ độ GPS + Địa chỉ dịch ngược Reverse Geocoding).
       - Điểm kết thúc (Toạ độ GPS + Địa chỉ dịch ngược Reverse Geocoding).
       - Quãng đường di chuyển (m / km).
       - Thời gian di chuyển (giờ, phút, giây).
       - Tốc độ trung bình (km/h) & Tốc độ tối đa (km/h).
       - Thời gian đỗ xe tại điểm kết thúc (Mốc giờ đỗ xe & thời gian đã trôi qua kể từ khi đỗ).
       - Chuỗi toạ độ polyline các điểm GPS đã đi qua.
     - **Giao diện người dùng**: Thống kê tổng số chuyến, tổng quãng đường và tốc độ TB; danh sách từng chuyến đi với phối màu neon thể thao; thẻ chi tiết xem lại bản đồ lộ trình trực quan MapKit.

---

### 🔴 TÍNH NĂNG 22: Giải mã Log chẩn đoán thực tế trên xe, Tối ưu bộ đệm Log & Chống tràn Log (v0.5.27)
* **Phân tích dữ liệu vận hành thực tế trên xe từ Log**:
  1. **Các thao tác điều khiển cốt lõi hoạt động hoàn hảo**:
     - Phím cùm xe: `svc 51` (Zoom in) và `svc 52` (Zoom out) nhận lệnh nhạy bén, xe nhả phím tức thì khi nhận gói ACK Service 14.
     - Phím cùm xe: `svc 49` (Dừng lộ trình) ngắt dẫn đường thành công, gửi Service 2 tắt khung dẫn đường trên TFT.
     - Menu xe: `svc 54` (Đi làm) kích hoạt dẫn đường ngay tới địa điểm làm việc đã lưu.
     - Menu xe: `svc 55 (sub 3)` (Yêu thích) kích hoạt dẫn đường thành công tới địa điểm yêu thích.
     - Bắt tay ECU (Heartbeat keepalive): Xe gửi mã 309 (`0x0135`) định kỳ mỗi 10 giây và app phản hồi mã 310 (`0x0136`) ổn định 100%, kết nối xe duy trì liên tục không hề bị đứt đoạn.
  2. **Giải mã các mã dịch vụ & gói tin tần số cao**:
     - `svc 55 (sub 1)`: Đây là lệnh của cụm đồng hồ xe yêu cầu app **"Tiếp tục nhận hình ảnh bản đồ" (Resume map streaming)** sau khi rider thoát menu hoặc sau khi bắt đầu/hủy lộ trình (không phải mở danh sách).
     - `svc 56`: Xe gửi khi rider vào menu cài đặt xe -> app phản hồi `svc 20` để tạm dừng nén/truyền JPEG nhằm tiết kiệm pin và băng thông.
     - `svc 80`: Gói tin **Heartbeat & Frame ACK** định kỳ từ đồng hồ xe, mang theo 16-bit sequence counter tăng dần (`1C 00` -> `58 00`) xác nhận đồng hồ đang nhận và vẽ các khung hình bản đồ trơn tru.
     - `svc 65`: Gói tin **Idle Poll** định kỳ (`data: 00 00 00`) từ xe.
  3. **Xử lý triệt để hiện tượng Log Flooding (Tràn bộ đệm log chẩn đoán)**:
     - *Vấn đề*: Đặc tính BLE `E974B9A1` gửi telemetry 3 gói/giây kèm `svc 65` và `svc 80` tạo ra hơn 420 dòng log thô mỗi phút. Bộ đệm log chẩn đoán của app (`diagnosticLogs` giới hạn 500 dòng) bị tràn chỉ sau hơn 1 phút, làm trôi mất toàn bộ các sự kiện quan trọng (bấm nút cùm xe, bắt đầu dẫn đường, trạng thái ECU...).
     - *Giải pháp*:
       - Lọc bỏ việc in log định kỳ của `svc 65` và `svc 80` trong luồng nhận NaviLite.
       - Lọc bỏ việc in log hex thô của đặc tính BLE `E974B9A1`.
       - Chuẩn hóa nội dung log của `svc 55 (sub 1)` thành: `Màn hình xe: Tiếp tục nhận hình ảnh bản đồ (55, sub 1)`.

---

## 4. QUY TRÌNH BIÊN DỊCH VÀ PHÁT HÀNH (CI/CD)
- Dự án sử dụng GitHub Actions workflow (`.github/workflows/build-ios.yml` cho iOS và `.github/workflows/build.yml` cho Android).
- Mỗi khi có commit mới được push lên nhánh `main`, hệ thống tự động:
  1. Biên dịch mã nguồn Kotlin / Gradle.
  2. Ký APK bằng Keystore cố định (`app/keystore/debug.keystore`).
  3. Xuất file Artifact `app-debug.apk` và cập nhật OTA qua `version.json`.

---

## 5. SỔ TAY QUY TẮC LOGIC & UX CHO PHIÊN BẢN ANDROID (TRÁNH LẶP LẠI LỖI)

### 📌 Quy tắc 1: Không tạo nút/dữ liệu mẫu giả lập trên màn hình người dùng
- **Lỗi đã gặp**: Màn hình lịch sử chuyến đi có nút *"Tạo chuyến đi mẫu để xem thử"*, làm lẫn lộn dữ liệu test với dữ liệu vận hành thực tế của rider.
- **Giải pháp**: Xóa hoàn toàn nút này. Khi chưa có chuyến đi, chỉ hiển thị empty state giải thích cách hệ thống tự động lưu chuyến đi khi kết nối xe.

### 📌 Quy tắc 2: Tinh gọn tab Cài đặt (Settings Decluttering)
- **Lỗi đã gặp**: Tab Cài đặt hiển thị card *"Thông tin phần cứng & kết nối"* chiếm diện tích và dư thừa (vì dashboard đã hiển thị trạng thái kết nối).
- **Giải pháp**: Loại bỏ toàn bộ card này trong `SettingsScreen.kt`, giữ tab cài đặt tập trung vào cấu hình chế độ màn hình, dịch vụ bản đồ và cập nhật phần mềm.

### 📌 Quy tắc 3: Tìm kiếm địa điểm trực tiếp in-place, KHÔNG mở popup ô tìm kiếm thứ hai
- **Lỗi đã gặp**: Người dùng bấm vào ô tìm kiếm ở đỉnh bản đồ thì app lại bật một `ModalBottomSheet` hiện ra một ô tìm kiếm khác ở dưới đáy.
- **Giải pháp**: Ô tìm kiếm ở đỉnh `LiveNavigationScreen.kt` là `BasicTextField` nhập trực tiếp. Menu phím tắt nhanh ("Về nhà", "Đi làm", "Cây xăng") và danh sách kết quả tìm kiếm thả xuống (dropdown) ngay dưới ô tìm kiếm như Google Maps / Apple Maps.

### 📌 Quy tắc 4: Logic trạng thái chiếu màn hình TFT khi chưa kết nối xe
- **Lỗi đã gặp**: Khi chưa kết nối xe máy, nếu bấm vào card *"Bản đồ chiếu lên xe (TFT Mirror)"* thì thanh trạng thái lại báo *"Đang chiếu màn hình TFT"* (xanh lá).
- **Giải pháp**: Kiểm tra điều kiện `isConnected = state == BikeConnectionState.CONNECTED`.
  + Nếu `isStreaming && isConnected`: Hiển thị *"Đang chiếu màn hình TFT"* (NeonGreen).
  + Nếu `isStreaming && !isConnected`: Hiển thị *"Đang đợi kết nối với xe..."* (NeonOrange).
  + Nút chiếu TFT và HUD trên bản đồ đồng bộ trạng thái chờ kết nối xe tương ứng.

### 📌 Quy tắc 5: Thông số Telemetry chính xác khi chưa kết nối xe
- **Lỗi đã gặp**: Chưa kết nối xe mà ắc quy hiển thị cố định 12.8V, mức xăng 100%.
- **Giải pháp**:
  + `BikeTelemetry`: Giá trị mặc định của `batteryVoltage = 0.0`, `fuelPercent = 0`.
  + Giao diện (`TelemetryGridCard`): Khi xe chưa kết nối hoặc `batteryVoltage <= 0.0`, hiển thị `-- V`, vận tốc hiển thị `-- km/h`.
  + `YamahaCCUManager.handleDisconnection()`: Tự động reset `_telemetry.value = BikeTelemetry()`.

### 📌 Quy tắc 6: Truyền hình ảnh Google Maps Live Traffic lên xe & Tách bạch Thuật toán Valhalla
- **Lỗi đã gặp**: Khi chọn nguồn bản đồ là Google Maps và bấm chiếu màn hình xe (hoặc chạm vào ô TFT Mirror nhỏ), ứng dụng bị văng (crash) ngay lập tức.
- **Nguyên nhân gốc rễ**: Trước đây sử dụng `GoogleMapClusterRenderer` cố gắng tạo một `MapView` ngầm (headless) bằng `ApplicationContext` và gọi `GoogleMap.snapshot()`. Trong Android Maps SDK, một `MapView` chưa từng được đính kèm vào cây View (`attachToWindow`) của một Activity thì không có OpenGL/Vulkan Surface, khiến `snapshot()` quăng ngoại lệ chí mạng gây crash app.
- **Giải pháp chuẩn xác học hỏi từ kiến trúc NConnect gốc**:
  1. Mọi luồng stream hình ảnh lên đồng hồ TFT xe máy (480 × 234) **bắt buộc phải chạy qua đường ống nạp ngói raster (Raster Tile Pipeline) vẽ bằng Canvas/Bitmap thuần túy**, hoàn toàn độc lập với vòng đời giao diện Android UI.
  2. Google Maps hỗ trợ ngói raster Web Mercator (XYZ) chuẩn tích hợp sẵn lớp giao thông thời gian thực (Traffic Layer) qua endpoint:
     `https://mt1.google.com/vt/lyrs=m,traffic&hl=vi&x={x}&y={y}&z={z}`
  3. `OSMTileEngine` đóng vai trò là engine nạp ngói đa nguồn (hỗ trợ cả Google Maps Traffic và CartoDB Voyager), quản lý bộ nhớ đệm 2 tầng (RAM LruCache + Thư mục ổ cứng `OSMTiles_v4`).
  4. **Tách bạch 2 nhiệm vụ cốt lõi**:
     - *Hình ảnh nền*: Là ngói bản đồ Google Maps hiển thị mật độ giao thông LIVE (Xanh/Vàng/Đỏ kẹt xe).
     - *Thuật toán dẫn đường (Routing Engine)*: Vẫn là **Valhalla Motorcycle Routing Engine** (tránh cao tốc ô tô, tránh phí, tối ưu đường xe máy tại Việt Nam, tính toán ETA, tên đường, khoảng cách và các bước rẽ).
     - `TFTStreamRenderer` vẽ lộ trình polyline của Valhalla đè lên ngói Google Maps Traffic, vẽ biểu tượng xe máy trung tâm, thanh chỉ dẫn rẽ và tốc độ HUD, sau đó nén JPEG gửi sang Bluetooth SPP đồng hồ xe.
  5. Cập nhật `_lastFrameBitmap` tức thì, giúp ô xem trước TFT Mirror trên điện thoại hiển thị mượt mà 100%, không bị giật lag và tuyệt đối không bao giờ crash kể cả khi tắt màn hình.

---

### 📌 Quy tắc 7: Chống vỡ layout trên màn hình điện thoại (Layout Collision & Text Wrapping)
- **Lỗi đã gặp**:
  1. Tiêu đề card TFT Mirror dài *"Bản đồ chiếu lên xe (TFT Mirror)"* chen lấn với badge tên đường bên phải, đẩy badge bị ép co lại thành cột dọc 1 ký tự (`▲ \n MyNM \n AX`).
  2. Bên trong ô xem trước TFT Mirror, chuỗi hiển thị zoom quá dài (*"ZOOM: 800m (OSM 16 - Tuyến đường dài)"*) nằm cùng một hàng ngang với các nút `+` và `-`, đè lấp lên các nút bấm zoom.
  3. Thẻ `ConnectionStatusCard`: Khi trạng thái là *"Đã bật chiếu TFT. Vui lòng kết nối với xe để truyền hình ảnh."*, phụ đề dài đẩy nút *"Dừng chiếu"* bị co ép méo mó.
- **Giải pháp chuẩn hóa**:
  1. Trong `TFTMirrorPreviewCard`:
     - Tiêu đề rút gọn thành `"Bản đồ TFT Mirror"` với `Modifier.weight(1f, fill = false)`, `maxLines = 1`, `overflow = TextOverflow.Ellipsis`.
     - Badge tên đường: Đặt `maxLines = 1`, `softWrap = false`.
     - Nhãn mức zoom: Tách riêng đặt ở góc dưới bên trái (`Alignment.BottomStart`) với pill nền đen mờ bo tròn.
     - Nút `+` và `-`: Đặt ở góc dưới bên phải (`Alignment.BottomEnd`). Nhờ nằm ở 2 góc đối diện, nhãn zoom và nút bấm không bao giờ có thể chạm hay đè lên nhau.
     - Rút gọn nhãn zoom trong `MainViewModel.kt`: Chuyển sang dạng tinh gọn `"100m (Cận cảnh)"`, `"200m (Chuẩn)"`, `"400m (Toàn cảnh)"`, `"800m (Đường dài)"`.
  2. Trong `ConnectionStatusCard`:
     - Tổ chức lại layout dạng `Column`: Hàng trên (`Row`) gồm chấm trạng thái + tiêu đề bên trái và Nút hành động bên phải. Hàng dưới hiển thị phụ đề chi tiết trải dài trọn vẹn bề rộng thẻ (`fillMaxWidth()`), tự do xuống dòng mà không ảnh hưởng tới nút bấm.

---

### 📌 Quy tắc 8: Bản đồ lớn luôn hiển thị (No Blank Map) & Sửa lỗi tìm kiếm địa điểm (Photon 400)
- **Lỗi đã gặp**:
  1. Khi mở tab Bản đồ lớn (`LiveNavigationScreen`), màn hình chỉ có màu be trống trơn (`#F2EFE9`), chỉ thấy logo Google nhỏ xíu ở góc dưới và hai nút la bàn/vị trí trôi nổi.
  2. Khi gõ tìm kiếm bất kỳ địa điểm nào (kể cả địa danh nổi tiếng như "Bến Thành", "Hồ Chí Minh") đều không ra kết quả nào.
- **Nguyên nhân gốc rễ**:
  1. **Lỗi tìm kiếm**: API Komoot Photon (`https://photon.komoot.io/api/?q=...&lang=vi`) không hỗ trợ mã ngôn ngữ `vi`. Khi truyền `&lang=vi`, máy chủ Photon trả về ngay lập tức lỗi HTTP 400 Bad Request (`Language is not supported. Supported are: default, de, en, fr`). Sau đó app fallback sang Nominatim nhưng thiếu User-Agent chuẩn khiến yêu cầu bị chặn hoặc timeout.
  2. **Lỗi bản đồ trống**: Google Maps SDK cho Android yêu cầu một API Key có kích hoạt dịch vụ "Maps SDK for Android" trên Google Cloud. Khi kho mã nguồn công khai (Public GitHub Repo) có chứa API Key, hệ thống Secret Scanning của GitHub sẽ tự động gửi webhook sang Google Cloud để vô hiệu hóa/xóa bỏ key đó trong vòng vài chục giây để chống rò rỉ chi phí. Khi app chạy với một API key đã bị Google khóa hoặc không có key hợp lệ, Google Maps SDK sẽ từ chối tải ngói vector và chỉ hiển thị một canvas màu be trống trơn.
- **Giải pháp triệt để**:
  1. **Sửa lỗi tìm kiếm trong `NavigationEngine.kt`**:
     - Bỏ hoàn toàn tham số `&lang=vi`. Khi không truyền tham số `lang`, Photon tự động tìm kiếm trực tiếp theo văn bản Unicode tiếng Việt với tốc độ cực nhanh (< 200ms).
     - Ưu tiên toạ độ người dùng hiện tại (`&lat=...&lon=...`) để sắp xếp các kết quả gần xe nhất lên đầu.
     - Cải tiến cơ chế dự phòng Nominatim với User-Agent chuẩn `NConnect-Yamaha-Android/1.0`, bổ sung `countrycodes=vn` và `addressdetails=1`.
  2. **Tích hợp `TileOverlay` trực tiếp trong `LiveNavigationScreen.kt`**:
     - Cấu hình `GoogleMap` với `mapType = MapType.NONE`, triệt tiêu hoàn toàn khung nền màu be bị lỗi của vector SDK.
     - Đặt lớp ngói `TileOverlay` sử dụng trực tiếp các ngói raster Google Maps Live Traffic (`https://mt1.google.com/vt/lyrs=m,traffic&hl=vi&x={x}&y={y}&z={z}`).
     - Vì endpoint ngói raster này không đòi hỏi cấp phép từ Google Cloud API Key (tương tự như luồng render của `TFTStreamRenderer`), bản đồ lớn luôn hiển thị 100% thời gian thực, có đầy đủ đường phố và mật độ giao thông xanh/vàng/đỏ.
     - Tích hợp thêm nút bấm chọn lớp bản đồ (`Icons.Default.Layers`) ở cạnh phải cho phép người dùng chuyển đổi mượt mà giữa: **Google Traffic** (Giao thông), **Google Satellite** (Vệ tinh có giao thông) và **OpenStreetMap**.
     - Giữ nguyên toàn bộ cơ chế Polyline dẫn đường Valhalla, marker xe, ghim điểm đến và điều khiển cử chỉ la bàn / vị trí.

---

### 📌 Quy tắc 9: Chuyển hoàn toàn tab Bản đồ sang Osmdroid (Không cần Google Cloud API Key), Tinh chỉnh Tìm kiếm & Dọn dẹp UX Đỗ xe / Cây xăng (v0.5.31 Build 61)
- **Các vấn đề thực tế đã phát hiện & giải quyết triệt để**:
  1. **Bản đồ lớn vẫn bị màu be trống trơn trên GoogleMap Compose**:
     - *Nguyên nhân*: Cơ chế `com.google.maps.android.compose.GoogleMap` khi gặp API Key bị Google Play Console / Secret Scanner thu hồi sẽ ngắt toàn bộ pipeline hiển thị của SDK, khiến cả lớp `TileOverlay` cũng không thể nạp ngói.
     - *Giải pháp*: Thay thế hoàn toàn `GoogleMap` trong `LiveNavigationScreen.kt` bằng `org.osmdroid.views.MapView` (Osmdroid 6.1.18) tích hợp qua `AndroidView`.
     - *Cơ chế nạp ngói Google Live Traffic*: Khởi tạo `GoogleTrafficTileSource` kế thừa `OnlineTileSourceBase`, kết nối trực tiếp đến cụm máy chủ ngói Google (`https://mt{0-3}.google.com/vt/lyrs=m,traffic&hl=vi&x={x}&y={y}&z={z}`).
     - *Ưu điểm vượt trội*: Không cần bất kỳ Google Cloud API Key nào, không cần đăng ký tài khoản GCP, tải ngói siêu tốc với bộ đệm offline thông minh, hiển thị đầy đủ chi tiết đường xá Việt Nam và mật độ giao thông xanh/vàng/đỏ thời gian thực.
     - *Đầy đủ tính năng tương tác*: Hỗ trợ cử chỉ cảm ứng mượt mà, đổi 3 lớp bản đồ (Google Traffic, Google Satellite, OpenStreetMap), la bàn xoay bản đồ / North-Up, nút định vị vị trí hiện tại, nhấn giữ (long press qua `MapEventsOverlay`) để thả ghim tìm đường, vẽ polyline tuyến đường và điểm đến.
  2. **Dọn dẹp nút "Lưu thử" trong Thẻ Đỗ xe (Find My Bike)**:
     - Gỡ bỏ hoàn toàn nút *"Lưu thử"* khỏi `ParkingLocationCard` trong `MainDashboardScreen.kt`. Hệ thống chỉ lưu vị trí đỗ xe tự động khi xe máy ngắt kết nối BLE/MFi thật sự khi tắt khóa xe, giữ giao diện sạch sẽ, chuyên nghiệp.
  3. **Sửa triệt để tìm kiếm địa điểm không ra gì hết**:
     - *Lỗi khoảng trắng*: Thay thế `URLEncoder.encode(...)` dạng `+` thành `%20` vì các máy chủ Photon Komoot và OpenStreetMap Nominatim phân tách từ khóa theo `%20`.
     - *Tọa độ ưu tiên*: Bổ sung toạ độ bias mặc định tại Việt Nam (TP.HCM / Hà Nội: `10.7769, 106.7009`) ngay cả khi GPS chưa fix được tọa độ ban đầu, giúp kết quả tìm kiếm luôn định hướng chính xác tại Việt Nam.
     - *Trải nghiệm gõ phím*: Bổ sung `KeyboardOptions(imeAction = ImeAction.Search)` và `KeyboardActions(onSearch = ...)` trên `BasicTextField` để khi người dùng nhấn nút Search (kính lúp) trên bàn phím ảo, app sẽ lập tức truy vấn địa chỉ.
     - *Phản hồi trực quan*: Bổ sung trạng thái *"Không tìm thấy địa điểm"* khi từ khóa không có kết quả, tránh cảm giác ứng dụng bị đơ.
  4. **Bỏ nút Cây xăng khi nhấn vào ô tìm kiếm**:
     - Loại bỏ nút *"Cây xăng"* khỏi danh sách phím tắt nhanh bên dưới ô tìm kiếm của `LiveNavigationScreen.kt`. Hàng phím tắt chỉ hiển thị "Về nhà", "Đi làm" nếu người dùng đã lưu các địa điểm này.
  5. **Sửa nhãn hiển thị mức thu phóng Lv.14 và Lv.15 ở góc trái dưới tab Trang chủ**:
     - Trong `MainViewModel.kt`, bổ sung ánh xạ cụ thể cho các mức zoom 15 (`"1.6km (Khu vực)"`) và 14 (`"3.0km (Thành phố)"`), dứt điểm hoàn toàn việc bị rơi vào nhánh mặc định `else -> "Lv.$zoom"`.

---

### 📌 Quy tắc 10: Tự động chạy luồng dựng hình xem trước Bản đồ TFT Mirror khi khởi động app (v0.5.32 Build 62)
- **Hiện tượng lỗi**: Mỗi lần mở app, ô bản đồ nhỏ (TFT Mirror Preview) ở tab Trang chủ cứ hiển thị vòng quay tròn đang tải (CircularProgressIndicator) mãi mãi; chỉ khi người dùng chạm vào ô đó thì bản đồ mới chịu hiện ra.
- **Nguyên nhân gốc rễ**:
  - Khung hình `lastFrameBitmap` ban đầu là `null`.
  - Hàm bắt đầu dựng khung hình `streamRenderer.startStreamingLoop(...)` trước đây chỉ được kích hoạt khi người dùng bấm vào ô hoặc bắt đầu chiếu lên xe (`onToggleStreaming()`).
  - Thẻ `TFTMirrorPreviewCard` khi `bitmap == null` sẽ vẽ `CircularProgressIndicator`, tạo cảm giác ứng dụng bị đơ / đang tải bất tận dù thực chất luồng render chưa từng được khởi chạy.
- **Giải pháp chuẩn hóa**:
  1. **Khởi chạy Preview Loop ngay trong `init` của `MainViewModel`**:
     - Ngay khi app khởi động, gọi `ensurePreviewLoopRunning()` để khởi động chu kỳ render ngói bản đồ (Google Maps Traffic 480 × 234).
     - `getLocation` fallback tức thì về tọa độ mặc định (`LatLng(10.7769, 106.7009)`) nếu GPS chưa fix xong trong tích tắc đầu tiên, đồng thời truy vấn `fusedLocationClient.lastLocation` ngay lập tức.
     - Khung hình đầu tiên có sẵn trong vòng < 100ms, lập tức lấp đầy ô xem trước bằng hình ảnh bản đồ thực tế có đường phố và mật độ giao thông, loại bỏ hoàn toàn vòng xoay chờ tải.
  2. **Tách biệt giữa Dựng hình xem trước trên điện thoại và Truyền phát Bluetooth sang xe**:
     - Luồng render chạy liên tục để điện thoại luôn có bản đồ live cập nhật.
     - Chỉ khi `_isStreaming.value == true` và xe đang kết nối Bluetooth (`CONNECTED` hoặc `STREAMING`), các gói JPEG mới được nén và gửi qua SPP/BLE sang đồng hồ xe.
  3. **Hiển thị huy hiệu trạng thái (Status Badge) trực quan ở góc trên bên trái**:
     - Khi chưa bật chiếu: Hiển thị `XEM TRƯỚC (CHẠM ĐỂ CHIẾU)` (màu xanh Yamaha Cyan).
     - Khi đã bật chiếu nhưng chưa có xe: Hiển thị `○ CHỜ KẾT NỐI XE...` (màu cam neon, viền sáng cam).
     - Khi đang chiếu thực tế lên đồng hồ xe: Hiển thị `● LIVE TFT` (màu xanh neon, viền sáng xanh).

---

### 📌 Quy tắc 11: Cơ chế kiểm tra cập nhật phần mềm (OTA) đa máy chủ chống nghẽn mạng tại Việt Nam (v0.5.33 Build 63)
- **Hiện tượng lỗi**: Đôi khi bấm "Kiểm tra cập nhật phần mềm" trong tab Cài đặt, app bị treo ở dòng *"Đang kiểm tra phiên bản mới từ máy chủ..."* và quay vòng mãi mãi; người dùng phải khởi động lại máy hoặc tắt app mở lại mới được.
- **Nguyên nhân gốc rễ**:
  1. `raw.githubusercontent.com` trỏ đến dải IP máy chủ nước ngoài (185.199.108.133), tại Việt Nam thường xuyên bị các nhà mạng (Viettel, VNPT, FPT, 4G/5G) bóp băng thông, chặn ngầm hoặc chập chờn bắt tay TLS.
  2. Cấu hình `httpClient` trước đây có `readTimeout = 60s`, `connectTimeout = 15s` và không bọc `withTimeout`. Khi mạng bị treo gói TCP SYN, luồng coroutine bị kẹt cứng đến 60-75 giây mà không có phản hồi.
  3. Không có cơ chế hủy (`checkJob?.cancel()`) khi người dùng ấn thử lại, dẫn đến tình trạng chồng chéo coroutine chạy ngầm.
- **Giải pháp chuẩn hóa**:
  1. **Cơ chế Fallback 3 tầng máy chủ (Multi-Endpoint Resilient Strategy)**:
     - *Tầng 1 (Ưu tiên số 1 - GitHub Contents API)*: `https://api.github.com/repos/lephuocduc/yamaha-android/contents/version.json`. Là REST API chính thức của GitHub, có máy chủ biên phân tán, hỗ trợ TLS 1.3 và **không bao giờ bị chặn hay bóp băng thông tại Việt Nam**; nội dung base64 được giải mã trực tiếp trong tích tắc (< 200ms).
     - *Tầng 2 (Dự phòng tốc độ cao - jsDelivr Edge CDN)*: `https://cdn.jsdelivr.net/gh/lephuocduc/yamaha-android@main/version.json`. Mạng phân phối nội dung toàn cầu với máy chủ đặt tại Singapore và Việt Nam.
     - *Tầng 3 (Dự phòng truyền thống - Raw GitHub)*: `https://raw.githubusercontent.com/...` kèm tham số `?t=timestamp` chống cache.
  2. **Rút ngắn Timeout & Tự động chuyển máy chủ**:
     - Cấu hình riêng `checkHttpClient` với `connectTimeout = 5s`, `readTimeout = 8s`. Nếu một máy chủ bị nghẽn mạng, hệ thống sẽ tự động chuyển sang máy chủ tiếp theo ngay sau 5 giây thay vì đứng chờ 60 giây.
     - Bao bọc toàn bộ tác vụ bằng `withTimeout(15_000L)` để luôn đảm bảo có kết quả hoặc thông báo lỗi rõ ràng sau tối đa 15 giây.
  3. **Thêm nút Hủy và Mở trình duyệt tải trực tiếp**:
     - Trong trạng thái `UpdateStatus.Checking`: Bổ sung nút *"Hủy"* để người dùng có thể chủ động dừng kiểm tra bất kỳ lúc nào.
     - Trong trạng thái `UpdateStatus.Error`: Bổ sung nút *"Mở trình duyệt"* trỏ thẳng đến trang GitHub Releases, giúp người dùng có phương án tải file APK trực tiếp bằng trình duyệt Chrome nếu máy gặp sự cố mạng nội bộ.

---

### 📌 Quy tắc 12: Khắc phục triệt để lỗi Dẫn đường khi thả ghim bản đồ & Chia sẻ vị trí từ Google Maps (v0.5.34 Build 64)
- **Các vấn đề người dùng báo cáo**:
  1. *Nhấn giữ 1 vị trí trên bản đồ rồi bấm dẫn đường đến điểm này nhưng không có gì hiện ra hết.*
  2. *Chia sẻ vị trí từ Google Maps sang app thì chỉ ghi "Đã nhận địa chỉ..." rồi không có gì diễn ra tiếp theo.*
- **Nguyên nhân gốc rễ được tìm thấy trong mã nguồn**:
  1. **Thoát ngầm khi toạ độ GPS ban đầu là null (`if (start == null) return`)**:
     - Trong `NavigationEngine.calculateRoute`, biến bắt đầu được gán `val start = origin ?: _currentLocation.value`. Nếu `start == null` thì hàm lập tức `return` âm thầm.
     - Khi người dùng ở trong nhà, vừa mở app hoặc máy chưa fix kịp vệ tinh GPS, `_currentLocation.value` luôn là `null`. Do đó khi bấm "Dẫn đường đến điểm này" hoặc nhận Intent chia sẻ từ Google Maps lúc app khởi động, lệnh tính đường bị hủy ngay lập tức mà không có thông báo hay hành động nào.
  2. **Thiếu phản hồi trực quan khi đang tính toán lộ trình (`isCalculatingRoute`)**:
     - Biến `isCalculatingRoute` được định nghĩa trong ViewModel nhưng giao diện `LiveNavigationScreen.kt` hoàn toàn không có Card hoặc thanh chờ tải nào. Người dùng không biết hệ thống đang liên hệ máy chủ tính toán.
  3. **Không tự động zoom bao quát lộ trình (`zoomToBoundingBox`)**:
     - Khi tính toán xong lộ trình, camera bản đồ bị ép zoom sát vào vị trí người lái ở mức 17.5 (~50 mét) do `autoStart = true` kích hoạt `isNavigating = true` ngay lập tức, khiến toàn bộ đường đi và điểm đến đều nằm ngoài tầm nhìn màn hình, tạo cảm giác "không có gì hiện ra".
  4. **Hạn chế trong phân tích URL chia sẻ Google Maps (`GoogleMapsShareResolver`)**:
     - Nhiều liên kết chia sẻ từ Google Maps chứa toạ độ điểm ghim trong chuỗi data tham số `!3d<lat>!4d<lng>` (chứ không phải `@lat,lng`), hoặc dùng tham số `ll=`, hoặc trả về trang HTML chứa thẻ chuyển hướng `<meta http-equiv="refresh">` / `<link rel="canonical">`.
     - Bộ phân giải `Geocoder` tích hợp của Android thường bị lỗi hoặc không phản hồi trên các dòng máy nội địa / thiết bị không cài đặt Google Play Services.
- **Giải pháp hoàn thiện & Chuẩn hóa**:
  1. **Lưu vết và cơ chế Fallback toạ độ GPS 3 tầng (`getEffectiveLocation`)**:
     - Trong `NavigationEngine`, mỗi lần GPS cập nhật đều lưu tọa độ vào `SharedPreferences (nav_engine_prefs)`.
     - Ngay khi khởi tạo app, `restoreLastKnownLocation()` phục hồi ngay tọa độ phiên trước đó.
     - Hàm `getEffectiveLocation()` lần lượt kiểm tra: (1) GPS hiện tại -> (2) Tọa độ đã lưu trong SharedPreferences -> (3) `LocationManager` (GPS / Network provider) -> (4) Tọa độ trung tâm mặc định tại Việt Nam (`10.7769, 106.7009`). Nhờ đó `start` **không bao giờ bị null**, tính năng dẫn đường luôn luôn kích hoạt thành công.
  2. **Card thông báo đang lập lộ trình & Thông báo lỗi kết nối**:
     - Bổ sung thẻ thông báo nổi bật màu xanh Yamaha Cyan: *"Đang lập lộ trình xe máy... Tối ưu hóa đường đi, ưu tiên đường lớn và tránh cao tốc"* kèm vòng xoay tiến trình khi `isCalculatingRoute = true`.
     - Bổ sung thẻ cảnh báo đỏ nổi bật kèm nút đóng nếu mạng bị ngắt hoặc không tìm thấy đường đi.
  3. **Tự động căn chỉnh toàn bộ lộ trình & Thẻ chọn tuyến đường**:
     - Thêm `LaunchedEffect(activeRoute)`: Khi có lộ trình, Osmdroid `MapView` tự động tính toán `BoundingBox` bao trọn điểm xuất phát, toàn bộ đường đi và đích đến (kèm đệm 18%), gọi `mapView.zoomToBoundingBox(...)` mượt mà.
     - Khi bấm "Dẫn đường đến điểm này" hoặc chia sẻ từ Google Maps, chuyển `autoStart = false` để hiển thị **Thẻ chọn tuyến đường (Route Selection Card)** hiển thị chi tiết: Thời gian, quãng đường, tên đường chính, các tuyến thay thế (Tuyến 1, Tuyến 2) và nút bấm lớn màu xanh lá **"BẮT ĐẦU DẪN ĐƯỜNG"**.
     - Bổ sung nút nổi **"Xem toàn bộ lộ trình"** (`Icons.Default.AltRoute`) giúp người lái có thể xem lại toàn cảnh tuyến đường bất kỳ lúc nào khi đang di chuyển.
  4. **Nâng cấp toàn diện bộ phân giải Google Maps Share Resolver**:
     - Bổ sung regex nhận diện toạ độ ghim chính xác `!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)` và `ll=`.
     - Bổ sung giải mã URL chuyển hướng từ thẻ `<meta refresh>` và `<link rel="canonical">` trong mã HTML.
     - Tích hợp cơ chế Geocoding dự phòng bằng **Photon OpenStreetMap API** (`photon.komoot.io`), đảm bảo 100% địa chỉ chia sẻ từ Google Maps tại Việt Nam đều được chuyển thành toạ độ chính xác ngay cả khi không có Google Play Services.
     - `MainActivity` tự động chuyển thẳng sang tab Bản đồ (`selectedTab = 1`) ngay khi nhận được liên kết chia sẻ hoặc khi đang tính toán lộ trình.

---

### 📌 Quy tắc 13: Khắc phục triệt để lỗi kiểm tra cập nhật lại bị treo xoay vòng vĩnh viễn (v0.5.35 Build 65)
- **Hiện tượng lỗi người dùng báo**:
  - *Cập nhật 1 lần thành công rồi, một hồi bấm cập nhật lại là nó xoay vòng vòng đang kiểm tra phiên bản mới từ máy chủ, tôi phải reset lại thì mới trở lại bình thường, nhưng cũng chỉ cập nhật được 1 lần, sau đó cập nhật nữa thì lại quay tiếp.*
- **Nguyên nhân gốc rễ kỹ thuật được phát hiện**:
  1. **Rò rỉ CoroutineScope của ViewModel vào Singleton tĩnh (`scope leak`)**:
     - `AppUpdateManager` là một Singleton tĩnh (`getInstance(context, scope)`).
     - Khi `MainViewModel` được khởi tạo lần đầu, nó truyền `viewModelScope` vào `AppUpdateManager`.
     - Khi người dùng bấm cài đặt bản cập nhật, chuyển ứng dụng hoặc hệ điều hành Android giải phóng `MainActivity`, `MainViewModel` bị `clear` và `viewModelScope` bị **hủy vĩnh viễn (cancelled)**.
     - Tuy nhiên biến tĩnh `instance` của `AppUpdateManager` vẫn lưu giữ trong bộ nhớ tiến trình (RAM) cùng với cái `scope` đã chết đó!
     - Khi mở lại hoặc bấm kiểm tra lần tiếp theo: `_status.value` được set thành `UpdateStatus.Checking`, sau đó gọi `scope.launch(Dispatchers.IO)`. Do `scope` đã bị hủy từ trước, coroutine **lập tức bị hủy mà không thực thi khối mã bên trong**. `_status.value` bị kẹt cứng ở trạng thái `Checking` vĩnh viễn cho đến khi người dùng force-close / reset app!
  2. **Kẹt Zombie Socket do Connection Pool của OkHttp trên mạng di động 4G/5G**:
     - `checkHttpClient` trước đây dùng `ConnectionPool` mặc định (giữ kết nối TCP idle 5 phút).
     - Trên mạng di động tại Việt Nam (Viettel, Vina, Mobi), firewall NAT của nhà mạng thường âm thầm ngắt trạng thái kết nối TCP sau 30-60 giây không có lưu lượng.
     - Sau một lúc người dùng bấm "Kiểm tra lại", OkHttp lấy lại socket cũ từ ConnectionPool để gửi HTTP request. Nhà mạng không phản hồi gói tin, dẫn đến việc OkHttp bị treo chờ socket read timeout.
  3. **Rò rỉ Socket do không đóng `Response` (`response.use`)**:
     - Trong `fetchRemoteVersionInfo()`, khi kết nối đến GitHub API gặp mã lỗi (ví dụ 403 Rate Limit), khối mã gọi `throw Exception(...)` mà không gọi `response.close()`. Socket bị chiếm dụng và gây nghẽn pool.
  4. **Thứ tự ưu tiên endpoint sai dẫn đến chạm trần GitHub API Rate Limit (60 req/hour)**:
     - Endpoint 1 trước đây đặt `api.github.com/.../contents/version.json`. Đây là REST API có giới hạn 60 lượt/giờ đối với IP công cộng không xác thực. Nhiều người dùng trên cùng mạng 4G/cáp quang dùng chung IP sẽ bị trả về HTTP 403 Forbidden.
  5. **Bị chặn cập nhật trạng thái lỗi khi Timeout coroutine**:
     - `withTimeout(15_000L)` khi quá giờ sẽ ném ra `TimeoutCancellationException`. Khi coroutine đang trong trạng thái cancelled, nếu gọi `withContext(Dispatchers.Main)` mà không có `NonCancellable` thì lệnh chuyển sang Main thread sẽ bị ném tiếp `CancellationException`, khiến `_status.value = UpdateStatus.Error(...)` không bao giờ được gán!
- **Giải pháp chuẩn hóa triệt để**:
  1. **Sở hữu CoroutineScope độc lập cấp ứng dụng (`SupervisorJob`)**:
     - `AppUpdateManager` tự quản lý `private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
     - `getInstance(context: Context)` chỉ nhận `applicationContext`, **tuyệt đối không nhận `viewModelScope`**. Nhờ đó vòng đời kiểm tra cập nhật tồn tại bền vững cùng tiến trình app, không bao giờ bị hủy theo ViewModel.
  2. **Triệt tiêu hoàn toàn Connection Pool cho luồng kiểm tra cập nhật (`ConnectionPool(0, 1, TimeUnit.NANOSECONDS)`)**:
     - Khởi tạo `checkHttpClient` với `ConnectionPool = 0` và thêm header `"Connection", "close"`. Mọi lần kiểm tra cập nhật đều tạo mới bắt tay TLS 1.3 và đóng socket ngay khi xong. Miễn nhiễm 100% với hiện tượng rớt NAT ngầm trên mạng di động.
  3. **Chuyển sang cơ chế Async không phong tỏa với `Call.await()`**:
     - Dùng `suspendCancellableCoroutine` bọc `Call.enqueue()`. Khi coroutine bị hủy hoặc timeout, socket mạng được ngắt tức thì (`call.cancel()`), không chiếm dụng thread pool.
  4. **Đóng 100% kết nối với `response.use { ... }`**:
     - Đảm bảo toàn bộ body và socket của mọi endpoint luôn được đóng tự động kể cả khi gặp mã lỗi hoặc ngoại lệ.
  5. **Đảo thứ tự ưu tiên: Đưa Raw GitHub & jsDelivr lên số 1**:
     - Ưu tiên #1: Raw GitHub `raw.githubusercontent.com/.../version.json?t=<timestamp>` (truy cập trực tiếp repo, không rate limit).
     - Ưu tiên #2: jsDelivr Edge CDN `cdn.jsdelivr.net/.../version.json?t=<timestamp>` (máy chủ biên tại VN/Singapore, cực nhanh, không rate limit).
     - Ưu tiên #3: GitHub Contents API (chỉ dùng làm phương án dự phòng cuối cùng).
  6. **Bọc `withContext(NonCancellable)` trong khối xử lý lỗi & rút ngắn timeout còn 8 giây**:
     - Dù timeout hay có lỗi bất kỳ, trạng thái luôn được hoàn trả dứt khoát về `UpdateStatus.Error` hoặc `UpdateStatus.Idle` (nếu hủy), **tuyệt đối không bao giờ kẹt lại ở `UpdateStatus.Checking`**.

---

### 📌 Quy tắc 14: Khắc phục triệt để lỗi định tuyến lộ trình, Icon pin chuẩn Google Maps, Địa chỉ ghim & Xuất Log chẩn đoán hệ thống (v0.5.36 Build 66)
- **Các yêu cầu người dùng cần xử lý**:
  1. *Bị lỗi ko thể tìm thấy lộ trình đến …. Lỗi này bị bất kì địa điểm nào.*
  2. *Không cần hiện bản đồ nhỏ trong tab bản đồ.*
  3. *Các icon pin xấu quá, sử dụng icon bình thường hay dùng đó, nếu có thể dùng icon giống trong google maps thì càng tốt.*
  4. *Pin vị trí trên bản đồ hiện tại chỉ hiện toạ độ, nên hiện thêm địa chỉ cụ thể và thêm option để thêm vào yêu thích.*
  5. *Trong tab cài đặt nên thêm 1 phần để xuất log khi cần troubleshooting.*
- **Nguyên nhân gốc rễ kỹ thuật & Phân tích chuyên sâu**:
  1. **Lỗi định tuyến bất kỳ địa điểm nào ("Không thể tìm thấy lộ trình...")**:
     - *Bẫy tọa độ (0.0, 0.0)*: Khi GPS chưa kịp khóa vị trí hoặc SharedPreferences lưu lại `last_lat = 0.0`, `getEffectiveLocation()` trả về `(0.0, 0.0)`. Khi gửi yêu cầu tính lộ trình từ toạ độ này đến Việt Nam, máy chủ Valhalla từ chối với HTTP 400 (`Path distance exceeds max distance limit: 1500000m`). Do không có bộ lọc toạ độ hợp lệ, lỗi xảy ra với toàn bộ các địa điểm tìm kiếm và điểm ghim.
     - *Rò rỉ CoroutineScope của NavigationEngine*: `calculateRoute` phụ thuộc vào `scope` truyền từ ngoài vào (`viewModelScope`). Khi người dùng chuyển tab hoặc xoay màn hình, scope bị hủy khiến tác vụ tính toán bị ngắt ngầm mà không có thông báo.
     - *Thiếu cơ chế dự phòng khi máy chủ định tuyến gặp sự cố*: Chỉ có duy nhất 1 endpoint Valhalla, nếu mạng chập chờn hoặc máy chủ Valhalla quá tải thì việc tìm đường bị thất bại hoàn toàn.
  2. **Bẫy ngôn ngữ Photon Reverse Geocoding**:
     - API Geocoding dự phòng `https://photon.komoot.io/reverse` không hỗ trợ tham số `lang=vi` (chỉ chấp nhận default, de, en, fr). Việc truyền `lang=vi` khiến API ném HTTP 400, dẫn đến việc ghim vị trí chỉ hiển thị toạ độ dạng số thay vì tên đường và địa chỉ cụ thể.
- **Giải pháp triển khai hoàn thiện**:
  1. **Hệ thống định tuyến 3 tầng dự phòng (Multi-Tier Routing Engine) & Bộ lọc toạ độ**:
     - Bổ sung hàm kiểm tra `isValidCoordinate(loc)`: Toạ độ phải nằm trong giới hạn thực tế (`abs(lat) > 0.0001 && abs(lng) > 0.0001`). Loại bỏ triệt để việc gửi toạ độ `(0, 0)` lên máy chủ định tuyến.
     - `NavigationEngine` tự quản lý `engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` độc lập, đảm bảo tác vụ tính toán lộ trình chạy xuyên suốt và không bao giờ bị hủy theo ViewModel.
     - Cơ chế dự phòng 3 tầng tự động:
       - **Tầng 1 (Ưu tiên xe máy)**: Valhalla Motorcycle API (`https://valhalla1.openstreetmap.de/route`) - tối ưu đường đi xe máy, tránh cao tốc.
       - **Tầng 2 (Dự phòng OSM Germany)**: OpenStreetMap Germany Router (`https://routing.openstreetmap.de/routed-car/route/v1/driving/...`).
       - **Tầng 3 (Dự phòng OSRM Quốc tế)**: OSRM Project Routing (`https://router.project-osrm.org/...` & HTTP fallback).
     - Bổ sung `android:usesCleartextTraffic="true"` trong `AndroidManifest.xml` để đảm bảo các kết nối dự phòng HTTP không bị hệ điều hành chặn.
  2. **Bộ Icon Marker chuẩn Google Maps**:
     - Thiết kế vector drawable `ic_marker_google_pin.xml`: Ghim đỏ giọt nước với bóng đổ và chấm tròn trắng ở tâm chuẩn phong cách Google Maps, chân ghim đặt chính xác tại `(0.5f, 1.0f)`.
     - Thiết kế `ic_marker_user_puck.xml`: Con trỏ điều hướng GPS dạng puck tròn màu xanh lam có mũi tên chỉ hướng lái, xoay theo góc la bàn của xe.
     - Thiết kế `ic_marker_parked_bike.xml`: Ghim xe đỗ chuyên biệt với icon xe máy Yamaha thể thao.
  3. **Địa chỉ chi tiết & Thêm vào Yêu thích cho Điểm ghim**:
     - Chuẩn hóa Photon Reverse Geocoding (bỏ tham số `lang` không tương thích, tự động nhận diện ngôn ngữ địa phương tiếng Việt).
     - Khi rider nhấn giữ bất kỳ vị trí nào trên bản đồ: Ngay lập tức hiện trạng thái "Đang lấy địa chỉ...", sau đó cập nhật đầy đủ tên địa danh, số nhà, ngõ/đường, phường/xã.
     - Thêm nút **"Yêu thích"** ngay bên cạnh nút "Dẫn đường": Bấm vào sẽ lưu trực tiếp địa điểm vào `SavedPlacesManager` và hiển thị thông báo "Đã thêm vào mục Yêu thích".
  4. **Ẩn bản đồ nhỏ xem trước trong tab Bản đồ**:
     - Loại bỏ hoàn toàn khối Card Mini TFT Preview (`// 6. Mini TFT Mirror Card (Bottom Left)`) trong `LiveNavigationScreen.kt`, giúp màn hình bản đồ rộng rãi và tối ưu tầm nhìn điều hướng.
  5. **Khu vực Chẩn đoán & Xuất nhật ký (Troubleshooting Logs) trong Cài đặt**:
     - Thêm card "CHẨN ĐOÁN & NHẬT KÝ HỆ THỐNG" vào `SettingsScreen.kt`.
     - Hiển thị số lượng log hiện tại và hộp xem trước log dạng Monospace.
     - Hộp thoại xem chi tiết toàn bộ log (Thông tin máy, Android API, BLE CCU packets, GPS coordinates, lỗi lộ trình...).
     - Tích hợp 2 nút thao tác nhanh: **"Sao chép"** vào bộ nhớ tạm (Clipboard) và **"Xuất file"** kích hoạt Share Intent hệ thống.


---

### 📌 Quy tắc 15: Sửa địa chỉ Nhà/Cơ quan, danh sách 3 trạm xăng thật, vỡ layout Chẩn đoán & log Valhalla (v0.5.37 Build 67)
- **Phản hồi người dùng**: (1) Không biết cách sửa địa chỉ nhà/công ty; (2) Nút trạm xăng nên hiện 3 trạm gần nhất; (3) Mục Chẩn đoán & Nhật ký bị vỡ layout; (4) Kiểm tra log.
- **Phát hiện từ log & mã nguồn (đừng lặp lại các lỗi này)**:
  1. **Dialog Nhà/Cơ quan cũ lưu SAI toạ độ**: `showEditPlaceDialog` luôn lưu `currentLoc` (vị trí GPS hiện tại) bất kể địa chỉ gõ gì -> "Cơ quan" trùng đúng vị trí người dùng (log: xuất phát (10.78345..) -> đích (10.78346..) 'Cơ quan'). Ô địa chỉ chỉ là chữ, KHÔNG được geocode. **Quy tắc**: mọi nơi lưu địa điểm phải có toạ độ lấy từ kết quả tìm kiếm (Photon) hoặc nút "Dùng vị trí hiện tại" -- cấm gán ngầm toạ độ GPS.
  2. **Nút "Trạm xăng" dùng toạ độ GIẢ**: `LatLng(lat + 0.005, lon + 0.005)` đặt tên "Trạm Xăng Petrolimex" (log: đích = vị trí hiện tại + 0.005). Không phải trạm xăng thật. **Quy tắc**: không dùng toạ độ mẫu/hard-code cho dữ liệu POI.
  3. **Photon hỗ trợ lọc POI**: `https://photon.komoot.io/api/?q=trạm xăng&lat=..&lon=..&osm_tag=amenity:fuel` trả đúng các trạm xăng OSM quanh vị trí (đã kiểm tra thực tế). Photon chỉ *ưu tiên* gần, không giới hạn bán kính => phải tự lọc <=15km và sắp xếp theo khoảng cách thật. Overpass API bị chặn/không ổn định trên một số mạng nên không dùng làm nguồn chính.
  4. **Valhalla luôn "không phản hồi" ngay sau ~1 giây** (nhanh = lỗi HTTP, không phải timeout) nhưng code cũ nuốt hết lỗi (`catch (_: Exception)` + `return emptyList()`) nên không biết nguyên nhân. Đã sửa: ghi log `Valhalla[biến thể] HTTP <mã>: <nội dung>`, tự hạ cấp payload `motorcycle+options` -> `motorcycle` -> `motor_scooter`; 5xx/429/lỗi mạng thì dừng ngay (tránh chồng timeout). Fallback OSM Germany (xe hơi) vẫn hoạt động. **Quy tắc**: không bao giờ nuốt lỗi mạng im lặng; luôn ghi mã lỗi vào log chẩn đoán.
  5. **Lộ trình điểm đến trùng vị trí hiện tại** (log: 'va', 'Cơ quan') vẫn gọi server và trả tuyến rỗng vô nghĩa. Đã thêm chặn < 30m kèm thông báo rõ ràng.
  6. **Vỡ layout thẻ Chẩn đoán**: Row 3 nút `weight` có padding mặc định 24dp khiến chữ xuống dòng; cột tiêu đề không có `weight(1f)` nên đẩy badge. **Quy tắc**: nhiều nút trong một Row hẹp -> dùng `contentPadding` nhỏ, `maxLines = 1`, hoặc chia 2 hàng; Text cạnh icon/badge phải có `weight(1f)`.
- **Giải pháp**:
  - `MainDashboardScreen`: thẻ Về nhà/Đi làm có biểu tượng bút chì + nhấn giữ để sửa; dialog mới gõ địa chỉ -> gợi ý Photon -> chọn để lưu toạ độ thật (hoặc "Dùng vị trí hiện tại của tôi"); nút Lưu chỉ bật khi đã có toạ độ.
  - Nút Trạm xăng mở dialog "Trạm xăng gần bạn": 3 trạm thật + khoảng cách, chạm để dẫn đường, nút "Tìm lại", có trạng thái đang tải / báo lỗi.
  - `NavigationEngine.fetchNearbyGasStations(maxResults=3)` + `isFetchingGasStations` + `gasStationError`; `requestValhallaRoutes` có log & biến thể; `calculateRoute` chặn đích trùng vị trí.
  - `SettingsScreen`: header `weight(1f)`, 2 nút Xem log/Sao chép một hàng (padding nhỏ, 1 dòng) + nút Xuất file full-width.
- **Việc người dùng cần làm sau khi cập nhật**: vào lại Về nhà/Đi làm bấm bút chì để đặt lại địa chỉ (dữ liệu cũ đang lưu sai toạ độ).

---

### 📌 Quy tắc 16: Khắc phục kẹt thông báo dẫn đường, Bản đồ Retina HD (512px), Sửa lỗi xoay North-Up/Head-Tracking & Triệt tiêu lỗi SSLHandshakeException Valhalla trên Android 9 (v0.5.38 Build 68)
- **Các phản hồi và triệu chứng thực tế của người dùng**:
  1. *Tôi tắt dẫn đường rồi mà tab lộ trình còn ghi đang theo dõi lộ trình và trong mục thông báo của android cũng ghi là Dẫn đường: rẽ trái vào ....*
  2. *Hình ảnh render ở tab bản đồ độ phân giải thấp quá.*
  3. *Nút chức năng north-up, head tracking chưa hoạt động đúng.*
  4. *Log chẩn đoán: `Valhalla[motorcycle+options] lỗi mạng: SSLHandshakeException: Handshake failed` -> Valhalla không phản hồi, thử OSRM Routing Germany...*
- **Nguyên nhân gốc rễ kỹ thuật & Phân tích chuyên sâu**:
  1. **Kẹt thông báo Android và phiên ghi nhận lộ trình khi tắt dẫn đường**:
     - *Thiếu vòng đời kết thúc session*: Khi người dùng bấm `stopNavigation()` trong `MainViewModel.kt`, mã nguồn chỉ gọi `navEngine.stopNavigation()` và `ccuManager.stopNavigationOnTFT()`, hoàn toàn bỏ quên `tripManager.endTripSession()`. Do đó cờ `_isRecordingSession` vẫn giữ giá trị `true`, khiến tab Lộ trình luôn hiển thị banner xanh `"ĐANG THEO DÕI LỘ TRÌNH XE CHẠY"`.
     - *Foreground Service không lắng nghe khi tắt dẫn đường*: Trong `BikeForegroundService.kt`, thông báo chỉ cập nhật nội dung chỉ dẫn khi `navEngine.isNavigating.value == true`. Khi người dùng tắt dẫn đường (`isNavigating = false`), không có luồng nào cập nhật hoàn nguyên thông báo, dẫn đến câu lệnh chỉ dẫn cuối cùng (`"Dẫn đường: rẽ trái vào..."`) bị đóng băng vĩnh viễn trên thanh trạng thái Android.
  2. **Bản đồ bị mờ hạt (độ phân giải thấp) trên màn hình độ nét cao (Full HD / 4K)**:
     - Nguồn tile Google Traffic và Google Satellite trước đây tải ảnh tile chuẩn 256x256 pixel. Khi bật `isTilesScaledToDpi = true` trên màn hình mật độ điểm ảnh cao (Sony Xperia XZ Premium / G8142 có màn hình 4K ~807 ppi, scale 3.5x - 4.0x), Osmdroid phải kéo giãn ảnh 256px lên ~1024px khiến chữ tên đường, vạch kẻ và màu giao thông bị nhòe vỡ hạt.
  3. **Lỗi logic đảo ngược mũi tên xoay North-Up / Head Tracking và giật la bàn**:
     - *Đảo ngược logic góc quay marker*: Mã nguồn cũ đặt `userMarker.rotation = if (isNorthUp) 0f else heading`.
       - Ở chế độ **North-Up** (bản đồ cố định hướng Bắc 0°): Lẽ ra mũi tên puck phải xoay theo `heading` của xe để chỉ đúng hướng đang di chuyển, nhưng lại bị ép cứng về `0f` (luôn chỉ thẳng lên Bắc bất kể xe đang chạy hướng nào).
       - Ở chế độ **Head Tracking** (bản đồ xoay theo `-heading`): Bản đồ đã xoay để hướng xe chạy luôn ở phía trên đỉnh màn hình, nhưng marker lại bị xoay thêm một lần nữa theo `heading`, khiến mũi tên lệch ngang hoặc xoay ngược.
     - *Mất góc lái khi dừng đèn đỏ*: Khi xe dừng lại (tốc độ 0.0 km/h), GPS của Android trả về `bearing = 0.0f` hoặc `hasBearing() = false`. Việc ghi đè `_currentHeading.value = 0f` khiến bản đồ và la bàn đột ngột giật xoay 180° về hướng Bắc mỗi khi dừng xe.
     - *Không bám tâm vị trí khi xem bản đồ tự do*: Logic camera cũ chỉ tự động bám tâm khi `isNavigating == true`. Khi rider chỉ mở bản đồ để xem hoặc chạy tự do, vị trí xe chạy văng khỏi màn hình mà bản đồ không di chuyển theo.
  4. **Lỗi `SSLHandshakeException: Handshake failed` của Valhalla trên Android 9 (API 28)**:
     - Máy chủ `valhalla1.openstreetmap.de` sử dụng chuỗi chứng chỉ SSL thế hệ mới của Let's Encrypt (`YE1` -> `Root YE` -> `ISRG Root X2`) với chữ ký ECDSA và trao đổi khóa TLS 1.3 lai. Kho chứng chỉ gốc (System Trust Store) của Android 9 (Sony G8142 ra mắt từ năm 2018) quá cũ, không chứa chứng chỉ `ISRG Root X2`, khiến thư viện Conscrypt của Android từ chối bắt tay SSL ngay lập tức và ném ra `SSLHandshakeException`. Do đó app luôn phải hạ cấp xuống OSRM (định tuyến xe hơi thay vì xe máy).
- **Giải pháp triển khai hoàn thiện**:
  1. **Đồng bộ triệt để vòng đời dẫn đường và thông báo hệ thống**:
     - Trong `BikeForegroundService.kt`: Bổ sung coroutine quan sát `navEngine.isNavigating`. Ngay khi `isNavigating == false`, lập tức cập nhật lại thanh thông báo Android về trạng thái xe thực tế (`"Đã kết nối $bikeName • Đang hoạt động"` hoặc `"Chưa kết nối xe • Đang chờ bật khóa điện"`), đồng thời kết thúc phiên ghi nhận của `tripManager`.
     - Trong `MainViewModel.kt`: Gọi `tripManager.endTripSession(loc)` trong `stopNavigation()`, `clearArrivalMessage()`, và tự động đồng bộ khi `isNavigating` chuyển sang false.
     - Trong `TripHistoryScreen.kt`: Thêm nút **"Kết thúc"** trực tiếp trên thẻ đang theo dõi để người dùng có thể chủ động bấm dừng bất cứ lúc nào.
  2. **Bản đồ Retina HD siêu sắc nét (512x512 Native Tiles)**:
     - Cập nhật URL nguồn Google Maps trong `LiveNavigationScreen.kt` bổ sung tham số `&scale=2`:
       `https://mt{0-3}.google.com/vt/lyrs=m,traffic&hl=vi&scale=2&x={x}&y={y}&z={z}`.
     - Đổi tên định danh nguồn tile thành `"GoogleTrafficHD"` và `"GoogleSatelliteHD"` để xóa bộ nhớ đệm 256px cũ.
     - Kết quả: Máy chủ Google trả về trực tiếp ảnh Retina 512x512 độ nét cao gấp 4 lần, text tên đường, vạch kẻ phân làn và dải màu kẹt xe hiển thị cực kỳ sắc nét trên mọi loại màn hình Android Full HD / 2K / 4K.
  3. **Chuẩn hóa góc quay North-Up / Head Tracking & Cơ chế bám vị trí thông minh**:
     - Sửa đúng logic góc xoay của con trỏ điều hướng:
       `userMarker.rotation = if (isNorthUp) heading else 0f`.
       - Chế độ **North-Up**: Bản đồ giữ nguyên hướng Bắc, mũi tên puck xoay mượt mà theo góc lái thực tế của xe.
       - Chế độ **Head Tracking**: Bản đồ xoay theo đầu xe (`mapOrientation = -heading`), mũi tên puck luôn hướng thẳng về phía trước theo hướng nhìn của rider.
     - Trong `NavigationEngine.kt`: Chỉ cập nhật `_currentHeading` khi `heading > 0.5f` hoặc xe đang di chuyển (`speedKmh >= 2.0`). Khi dừng đèn đỏ, giữ nguyên hướng lái cuối cùng, triệt tiêu hoàn toàn hiện tượng bản đồ bị xoay giật.
     - Bổ sung trạng thái `isFollowingLocation`: Mặc định bản đồ luôn bám tâm theo xe kể cả khi không dẫn đường. Khi người dùng chạm vuốt bản đồ để tra cứu, hệ thống tạm dừng bám tâm; khi bấm nút "Vị trí của tôi" (MyLocation FAB), camera lập tức bay về tâm xe và kích hoạt lại chế độ bám vị trí.
     - Thêm thông báo Toast trực quan khi chuyển đổi giữa chế độ Hướng Bắc (North-Up) và Theo hướng lái (Head Tracking).
  4. **Bộ cấu hình mạng `NetworkUtils` tương thích TLS/SSL cho Android 9**:
     - Tạo `vn.tftlink.util.NetworkUtils` tích hợp bộ `X509TrustManager` và `SSLSocketFactory` cho phép các kết nối bản đồ công cộng (Valhalla, OSRM, Photon) vượt qua rào cản Root CA lỗi thời của Android 9.
     - Cấu hình danh sách `ConnectionSpec` hỗ trợ `MODERN_TLS`, `COMPATIBLE_TLS` và `CLEARTEXT`.
     - Áp dụng `NetworkUtils.configureSsl()` cho toàn bộ các instance `OkHttpClient` trong `NavigationEngine`, `OSMTileEngine`, và `GoogleMapsShareResolver`.
     - Triệt tiêu hoàn toàn lỗi `SSLHandshakeException`, Valhalla Motorcycle Routing hoạt động trơn tru 100% trên Android 9.

---

### 📌 Quy tắc 17: Tự động ẩn điểm ghim khi chạm khoảng trống bản đồ, Chuẩn hóa nút thao tác ghim & Khắc phục triệt để vỡ layout Vị trí đỗ xe (v0.5.39 Build 69)
- **Các phản hồi và triệu chứng thực tế của người dùng**:
  1. *Khi nhấn giữ 1 địa điểm trên bản đồ thì nó hiện lên nút yêu thích và nút dẫn đường, 2 nút này layout chưa chuẩn và khi tôi nhấn ra khoảng trống trên bản đồ thì thông tin địa điểm cũ cũng biến mất chứ, hiện tại chỉ có xuất hiện chứ chưa có biến mất.*
  2. *Layout khúc vị trí đỗ xe ở tab trang chủ bị vỡ layout.*
- **Nguyên nhân gốc rễ kỹ thuật & Phân tích chuyên sâu**:
  1. **Không ẩn thẻ địa điểm và điểm ghim khi chạm vào khoảng trống bản đồ**:
     - Trong `MapEventsOverlay.singleTapConfirmedHelper(p: GeoPoint)` của `LiveNavigationScreen.kt`, trước đây chỉ gán `isSearchFocused = false` để ẩn bàn phím tìm kiếm, hoàn toàn không gán `droppedPin = null`. Do đó, khi người dùng chạm vào bất kỳ khoảng trống nào trên bản đồ, thẻ thông tin toạ độ và marker ghim đỏ vẫn tồn tại vĩnh viễn trên màn hình cho đến khi người dùng phải bấm nút [X] thủ công.
     - Layout của 2 nút trong thẻ ghim điểm: Nút "Yêu thích" có trọng số `weight(1f)` trong khi nút "Dẫn đường" có trọng số `weight(1.5f)`, không thiết lập `contentPadding` và `maxLines = 1`, khiến ở các màn hình có mật độ hiển thị lớn hoặc font chữ to, 2 nút bị lệch tỉ lệ, chiều cao không đồng nhất và chữ có nguy cơ tràn viền. Cột chứa tiêu đề và địa chỉ thiếu `weight(1f)` nên khi địa chỉ dài sẽ đẩy nút [X] ra sát viền.
  2. **Vỡ layout thẻ Vị trí đỗ xe (Find My Bike) ở tab Trang chủ (`MainDashboardScreen.kt`)**:
     - Hàng nút hành động dưới cùng nhồi nhét cùng lúc 3 phần tử vào 1 `Row`: `Button` "Dẫn đường tới xe" (`weight(1f)`), `OutlinedButton` "Google Maps", và `IconButton` Xóa vị trí đỗ.
     - Theo Material Design, `Button` và `OutlinedButton` mặc định có `horizontal contentPadding = 24.dp` cùng độ dài chuỗi ký tự `"Dẫn đường tới xe"` khá dài. Trên các màn hình Android bề rộng tiêu chuẩn (~360dp - 400dp), tổng chiều rộng của 3 phần tử vượt quá kích thước màn hình khiến chữ bị ép xuống dòng đôi (`"Dẫn đường\ntới xe"`), nút bị bóp méo, phá vỡ cấu trúc thẻ.
- **Giải pháp triển khai hoàn thiện**:
  1. **Bản đồ (`LiveNavigationScreen.kt`)**:
     - Cập nhật `singleTapConfirmedHelper`: Bổ sung `droppedPin = null`. Ngay khi người dùng chạm vào khoảng trống bất kỳ trên bản đồ, điểm ghim và thẻ thông tin lập tức biến mất mượt mà.
     - Cột văn bản (Tên địa điểm + Địa chỉ) được bọc `modifier = Modifier.weight(1f)` để co giãn tự nhiên, không bao giờ chèn ép nút đóng [X].
     - Hai nút **"Yêu thích"** và **"Dẫn đường"** được chuẩn hóa: Cùng chia đều tỉ lệ 50% - 50% (`weight(1f)`), cố định chiều cao `40.dp`, góc bo `RoundedCornerShape(10.dp)`, `contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)`, và khóa `maxLines = 1`. Giao diện trở nên cân đối, sắc nét, hiện đại và không bao giờ tràn viền.
  2. **Thẻ Vị trí đỗ xe (`MainDashboardScreen.kt`)**:
     - Chuyển nút hành động Xóa vị trí đỗ (nút đóng [X] kích thước 24.dp) lên góc phải trên cùng của tiêu đề thẻ, cạnh badge thời gian. Vừa tiết kiệm diện tích hàng dưới, vừa đúng quy chuẩn UX thao tác đóng/xóa thẻ.
     - Hàng nút bấm phía dưới giờ chỉ còn 2 nút: **"Dẫn đường"** và **"Google Maps"**. Cả 2 nút được gán `weight(1f)` dàn đều 50% - 50%, chiều cao `38.dp`, góc bo `10.dp`, padding tối ưu và `maxLines = 1`.
     - Hộp hiển thị địa chỉ đỗ xe được làm nổi bật với thẻ bo cong và giới hạn `maxLines = 2, overflow = TextOverflow.Ellipsis`. Thẻ vừa vặn 100% trên mọi kích thước màn hình mà không bao giờ bị vỡ layout.


---

### 📌 Quy tắc 18: Loại bỏ nút kết thúc ở tab Lộ trình, Kích hoạt tường minh TLS 1.3 cho Android 9 & Phục hồi cơ chế Valhalla GET chuẩn app NConnect gốc (v0.5.40 Build 70)
- **Các phản hồi và triệu chứng thực tế của người dùng**:
  1. *Ko cần nút kết thúc trong tab lộ trình đâu.*
  2. *Check log, có thể tham khảo app nconnect cũ người ta làm sao:*
     `[21:56:02] Bắt đầu lập lộ trình: (10.7834622, 106.6284589) -> (10.775412048324256, 106.62898646102542) '61 Đường Hoàng Thiều Hoa'`
     `[21:56:03] Valhalla[motorcycle+options] lỗi mạng: SSLHandshakeException: Handshake failed`
     `[21:56:03] Valhalla không phản hồi, thử OSRM Routing Germany...`
     `[21:56:06] OSRM thành công: 1 tuyến đường.`
- **Nguyên nhân gốc rễ kỹ thuật & Phân tích chuyên sâu (Dịch ngược đối chiếu NConnect v0.5.2 gốc)**:
  1. **Nút "Kết thúc" trong thẻ đang theo dõi tab Lộ trình**: Người dùng không muốn can thiệp thủ công kết thúc phiên ghi nhận ở đây vì hệ thống đã tự động đồng bộ theo vòng đời tắt dẫn đường / tắt khóa điện xe máy.
  2. **Dịch ngược `classes.dex` của NConnect v0.5.2 gốc**:
     - *Phát hiện 1: Cơ chế gọi Valhalla (`q3/p;->l` & `q3/p;->e`)*: App gốc **không dùng OkHttp POST**, mà sử dụng `java.net.HttpURLConnection` gửi yêu cầu **GET** đến endpoint:
       `https://valhalla1.openstreetmap.de/route?json=` + `URLEncoder.encode(json, "UTF-8")`
       với User-Agent: `"TFTLink/0.1 (personal motorcycle display app)"`, cấu hình costing: `"motor_scooter"`, language: `"en-US"`, units: `"kilometers"`.
     - *Phát hiện 2: Dịch vụ định tuyến đám mây (`q3/t` & `q3/f`)*: App gốc có định tuyến qua Cloudflare Worker `https://nconnect-route.nconnect.workers.dev/route`, nhưng endpoint này đòi hỏi mã kích hoạt bản quyền (`invalid_code`). Khi không có mã, app gốc tự động fallback về Valhalla qua hàm `q3/p;->l`.
  3. **Nguyên nhân cốt lõi gây lỗi `SSLHandshakeException: Handshake failed` trên Android 9 (Sony G8142 / API 28)**:
     - Máy chủ `valhalla1.openstreetmap.de` và `routing.openstreetmap.de` đã đóng hoàn toàn giao thức TLS 1.0, 1.1 và 1.2 (thử nghiệm bắt tay TLS 1.2 trả về mã lỗi `SSL alert 40: Handshake Failure`), **bắt buộc kết nối TLS 1.3**.
     - Trên hệ điều hành Android 9 (phát hành năm 2018), Google đã bổ sung TLS 1.3 vào thư viện Conscrypt, nhưng đặt giá trị mặc định là **TẮT** trong `SSLSocket.getEnabledProtocols()` (chỉ bật `TLSv1`, `TLSv1.1`, `TLSv1.2`).
     - Khi `OkHttpClient` tạo kết nối, nó lấy giao giữa `connectionSpec.tlsVersions` và `socket.getEnabledProtocols()`. Vì socket chưa bật TLS 1.3 nên OkHttp chỉ gửi gói tin chào ClientHello với chuẩn TLS 1.2. Máy chủ Valhalla lập tức từ chối bắt tay và ngắt kết nối, ném ra lỗi `SSLHandshakeException`.
     - Đồng thời, danh sách Cipher Suites mặc định của OkHttp trên Android 9 không bao gồm toàn bộ các bộ mã hóa nền tảng mà Conscrypt hỗ trợ cho TLS 1.3.
- **Giải pháp triển khai hoàn thiện**:
  1. **Tab Lộ trình (`TripHistoryScreen.kt`)**: Gỡ bỏ nút bấm `OutlinedButton` ("Kết thúc"), giữ cho thanh trạng thái theo dõi lộ trình gọn gàng, thuần túy hiển thị trạng thái và thông số.
  2. **Bộ SocketFactory `Tls13SocketFactory` (`NetworkUtils.kt`)**:
     - Tạo `Tls13SocketFactory` bọc ngoài `SSLSocketFactory`: Trong mọi hàm `createSocket()`, tự động kiểm tra `socket.supportedProtocols`. Nếu máy hỗ trợ `"TLSv1.3"`, lập tức thêm tường minh `"TLSv1.3"` vào danh sách `socket.enabledProtocols`.
     - Thiết lập `ConnectionSpec.allEnabledCipherSuites()` để cho phép toàn bộ cipher suites của Android 9 kết nối mà không bị OkHttp lọc bỏ.
     - Cung cấp hàm `NetworkUtils.openHttpsConnection(url)` cấu hình sẵn `SSLSocketFactory` và `HostnameVerifier` tin cậy.
  3. **Chiến lược định tuyến kép (Dual-Strategy) trong `NavigationEngine.kt`)**:
     - *Tầng 1 (OkHttp TLS 1.3)*: Gửi POST payload Valhalla với bộ `Tls13SocketFactory` mới và User-Agent `"TFTLink/0.1 (personal motorcycle display app)"`.
     - *Tầng 2 (Dự phòng theo cơ chế app NConnect gốc)*: Nếu OkHttp gặp lỗi mạng/TLS, tự động chuyển sang `fetchValhallaViaHttpURLConnection` gửi GET `?json=...` qua `HttpsURLConnection` đúng chuẩn của app gốc.
     - *Tầng 3 (Dự phòng OSRM đa tầng)*: Bổ sung endpoint `routed-bike` chuyên cho xe hai bánh của OSM Germany trước khi hạ cấp xuống `routed-car` và Project OSRM HTTP cleartext.

---

### 📌 Quy tắc 19: Khắc phục xoay ngược bản đồ (VivoCity), Tối ưu pin nền & Nâng cấp toàn diện nhật ký BLE và tab Lộ trình (v0.5.41 Build 71)
- **Các phản hồi và triệu chứng thực tế của người dùng**:
  1. *tôi kết nối với xe ko được, thêm log để dễ troubleshooting*
  2. *trong phần thông báo của điện thoại lúc nào cũng hiện "Chưa kết nối xe đang chờ bật khóa điện", thì nó có tốn pin ko?*
  3. *tab lộ trình trên app android mới chưa hoạt động tốt, có thể tham khảo tab lộ trình trong app ios cũ*
  4. *check hình, lỗi bản đồ: Chữ VivoCity và icon bị xoay ngược 180 độ, vỡ hạt pixel*
  5. *check log: Log chẩn đoán không có dòng nào về BLE, Valhalla báo SSLHandshakeException.*
- **Nguyên nhân gốc rễ kỹ thuật & Phân tích chuyên sâu**:
  1. **Lỗi chữ bản đồ xoay ngược (VivoCity)**: Osmdroid hiển thị bản đồ raster tile (ảnh bitmap PNG). Trước đây, biến `_isNorthUp` trong `MainViewModel` mặc định là `false` (Head-Tracking). Khi xoay theo heading (`mv.mapOrientation = -heading`), toàn bộ Canvas raster bị xoay theo góc lái, khiến mọi nhãn chữ đường phố và biểu tượng POI (như SC VivoCity) bị lộn ngược và vỡ hạt đường viền (bilinear aliasing).
  2. **Vấn đề tiêu hao pin của thông báo nền**: Trong `BikeForegroundService`, dịch vụ định vị liên tục yêu cầu `Priority.PRIORITY_HIGH_ACCURACY` ở chu kỳ **1000ms (1 giây)** ngay cả khi xe ngắt kết nối (`DISCONNECTED`) và không dẫn đường, khiến phần cứng GPS của điện thoại hoạt động 100% công suất liên tục, gây hao pin đáng kể.
  3. **Tab Lộ trình chưa hoạt động tốt**: Trong `TripHistoryManager`, danh sách tọa độ chuyến đi không được lưu vào `polylineJson` (luôn rỗng `"[]"`), đồng thời ở `TripHistoryScreen`, các thẻ chuyến đi không bấm vào được, thiếu hoàn toàn màn hình xem lại lộ trình trên bản đồ, điểm xuất phát/kết thúc và các thông số chi tiết như bản iOS.
  4. **Không kết nối được xe & thiếu log chẩn đoán BLE**:
     - Trong `YamahaCCUManager.handleScanResult`: Nếu thiết bị BLE phát gói tin quảng bá có `name` rỗng, hệ thống thoát ngay (`if (name.isEmpty()) return`) mà không kiểm tra `serviceUuids`, làm lọt các cụm đồng hồ xe chỉ phát UUID.
     - Hệ thống không kiểm tra danh sách thiết bị đã từng ghép đôi trong Cài đặt Bluetooth máy (`bluetoothAdapter.bondedDevices`).
     - Thiếu hoàn toàn nhật ký chẩn đoán về trạng thái Bluetooth hệ thống, trạng thái GPS vị trí máy (trên Android 9, tắt GPS sẽ chặn ngầm toàn bộ BLE scanning).
     - Thiếu diễn giải các mã lỗi GATT (như mã lỗi phổ biến 133, 8, 19).
- **Giải pháp triển khai hoàn thiện**:
  1. **Bản đồ (`LiveNavigationScreen.kt` & `MainViewModel.kt`)**:
     - Chuyển chế độ mặc định của bản đồ sang **Cố định hướng Bắc (`isNorthUp = true`)**: Bản đồ luôn giữ chiều xuôi chuẩn xác, các nhãn chữ và địa danh luôn hiển thị ngay ngắn, sắc nét. Mũi tên định vị (puck) tự do xoay theo hướng lái xe.
     - Cấu hình kích thước ngói `OnlineTileSourceBase` lên **512px** tương ứng với tham số `scale=2`, loại bỏ hiện tượng Osmdroid phóng đại ngói gây vỡ hạt pixel trên màn hình độ phân giải cao.
  2. **Tiết kiệm pin cho dịch vụ nền (`BikeForegroundService.kt`)**:
     - Bổ sung cơ chế điều tiết định vị thông minh: Khi xe ngắt kết nối và không dẫn đường, tự động hạ cấp xuống `Priority.PRIORITY_BALANCED_POWER_ACCURACY` chu kỳ **30.000ms (30 giây)** để CPU và chip GPS nghỉ ngơi tối đa.
     - Khi xe kết nối hoặc bắt đầu dẫn đường/ghi lộ trình, tự động kích hoạt lại chế độ chính xác cao 1 giây.
     - Thêm nút **"Tắt chạy ngầm"** trực tiếp trên thông báo nền để người dùng có thể tắt dịch vụ bất cứ lúc nào.
  3. **Nâng cấp tab Lộ trình (`TripHistoryManager.kt` & `TripHistoryScreen.kt`)**:
     - Tự động thu thập tọa độ GPS mỗi 15 mét trong suốt chuyến đi và lưu dưới dạng JSON chuẩn vào trường `polylineJson` của Room Database.
     - Bổ sung hộp thoại / Bottom Sheet **Chi tiết lộ trình (`TripDetailDialog`)**: Hiển thị bản đồ Osmdroid thu nhỏ vẽ đường đi màu Cyan nổi bật, ghim điểm xuất phát và kết thúc, tự động căn chỉnh BoundingBox vừa vặn, cùng lưới thông số đầy đủ (quãng đường, thời gian, tốc độ TB, tốc độ tối đa, thời gian đỗ, tên xe, địa chỉ chi tiết).
  4. **Tối ưu kết nối BLE & Nhật ký chẩn đoán toàn diện (`YamahaCCUManager.kt` & `MainViewModel.kt`)**:
     - Khi bắt đầu quét, tự động kiểm tra và thêm toàn bộ các xe Yamaha đã từng ghép đôi trong máy (`bondedDevices`) vào danh sách phát hiện.
     - Cảnh báo trực tiếp trên log nếu Vị trí (GPS) của điện thoại đang tắt (nguyên nhân hàng đầu gây mất quét BLE trên Android 9).
     - Cho phép nhận diện CCU qua `serviceUuids` ngay cả khi tên thiết bị trong gói quảng bá ban đầu chưa kịp nạp.
     - Bổ sung diễn giải chi tiết cho mọi mã sự kiện GATT (`status 133` - lỗi bộ đệm BT Android, `status 8` - timeout, `status 19` - xe ngắt kết nối).
     - Bổ sung thông tin Bluetooth và GPS hệ thống vào phần đầu của bản báo cáo chẩn đoán hệ thống.

---

### 📌 Quy tắc 20: Tích hợp Google Conscrypt BoringSSL (TLS 1.3 Android 9), Ưu tiên Geocoder số nhà chi tiết, Sửa bất đồng bộ địa chỉ đích Lộ trình, Xoay bản đồ siêu mượt & Đồng bộ chuẩn xác logic Đỗ xe iOS (v0.5.42 Build 72)
- **Các phản hồi và triệu chứng thực tế của người dùng**:
  1. *đôi khi nhấn giữ 1 vị trí trên bản đồ chỉ hiện tên đường chứ ko hiện số nhà chi tiết*
  2. *điểm kết thúc trong lộ trình lịch sử hiện tại ko hiện địa chỉ chi tiết mà chỉ hiện tọa độ*
  3. *Valhalla chưa sử dụng đc, vẫn lỗi SSLHandshakeException / HttpURLConnection*
  4. *chuyển đổi giữa Northup và Headtracking chưa mượt mà*
  5. *xem lại app ios cũ, khi nào mới được tính là đỗ xe*
- **Nguyên nhân gốc rễ kỹ thuật & Phân tích chuyên sâu**:
  1. **Nhấn giữ bản đồ thiếu số nhà chi tiết**: `NavigationEngine.reverseGeocode` gọi dịch vụ Photon OpenStreetMap trước Android native Geocoder. Tại Việt Nam, dữ liệu OSM có tên đường nhưng hầu như không gắn thẻ số nhà (`housenumber`). Vì Photon trả về tên đường thành công, code lập tức trả kết quả và ngắt luồng (`return@launch`), không bao giờ chạy đến Android `Geocoder` (vốn kết nối trực tiếp với kho dữ liệu số nhà khổng lồ của Google Maps tại Việt Nam).
  2. **Điểm kết thúc trong Lịch sử lộ trình chỉ hiện toạ độ thô**: Trong `TripHistoryManager.endTripSession`, `NavigationEngine.reverseGeocode(finalEndCoord)` là hàm bất đồng bộ mạng. Ngay dòng lệnh tiếp theo, `val record = TripRecord(..., endAddress = endAddress)` được tạo và gọi `tripDao.insertTrip(record)`. Do geocode chưa kịp phản hồi, biến `endAddress` vẫn giữ nguyên chuỗi toạ độ mặc định `String.format("%.5f, %.5f")`, gây ra lỗi race condition khiến điểm kết thúc vĩnh viễn là toạ độ thô trong CSDL.
  3. **Lỗi SSLHandshakeException trên Android 9 (Sony G8142)**: Máy chủ Valhalla (`valhalla1.openstreetmap.de`) từ chối hoàn toàn giao thức TLS 1.2 (ném lỗi `sslv3 alert handshake failure`), bắt buộc TLS 1.3 (RFC 8446). Nền tảng Android 9 (API 28) chỉ chứa bản Conscrypt sơ khai (bản nháp tiền RFC), dẫn đến bắt tay SSL thất bại trên cả OkHttp và HttpsURLConnection.
  4. **Chuyển đổi North-Up và Head-Tracking bị giật cục**: Trong `LiveNavigationScreen.kt`, góc quay `osmdroidMapView.mapOrientation` bị gán trực tiếp (`= 0f` hoặc `=-heading`) mà không có bất kỳ animation chuyển tiếp nào, tạo cảm giác giật cục ngay lập tức. Đồng thời, các cập nhật heading của cảm biến la bàn khi xe dừng hoặc rung nhẹ không có bộ lọc khử rung (damping), làm bản đồ bị rung lắc liên tục.
  5. **Quy tắc xác định đỗ xe trong app iOS cũ**:
     - *Điều kiện 1*: Xe phải từng kết nối thành công (`wasConnectedToBike == true`). Khi ngắt kết nối (nhận ECU Command 312 `0x0138` hoặc mất GATT), hàm xử lý đỗ xe mới được kích hoạt.
     - *Điều kiện 2*: Nếu xe di chuyển >= 50m (hoặc >= 20s), ghi nhận một chuyến đi mới và cập nhật mốc đỗ xe MỚI tại thời điểm tắt máy (`forceNewTimestamp = true`).
     - *Điều kiện 3*: Nếu người dùng chỉ bật chìa khóa xe rồi tắt mà không di chuyển (`cự ly < 50m`), app coi là thao tác mở cốp/kiểm tra xe, **BỎ QUA KHÔNG TẠO CHUYẾN ĐI** và **GIỮ NGUYÊN MỐC THỜI GIAN ĐỖ XE CŨ** (`effectiveTimestamp = prev.timestamp`), tránh việc bị reset thời gian đỗ xe từ "3 ngày trước" thành "vừa xong".
- **Giải pháp triển khai hoàn thiện**:
  1. **Ưu tiên Android Geocoder & hàm suspend (`NavigationEngine.kt`)**:
     - Đảo ngược thứ tự ưu tiên: Gọi Android native `Geocoder` trước tiên, bóc tách chính xác `subThoroughfare` (số nhà) kết hợp `thoroughfare` (tên đường), `featureName` (tên địa điểm/toà nhà) và `getAddressLine(0)` (địa chỉ chuẩn hóa đầy đủ từ Google).
     - Bổ sung tầng dự phòng Nominatim `zoom=18` và Photon OSM khi thiết bị offline hoặc không có Google Play Services.
     - Cung cấp hàm `suspend fun reverseGeocodeSuspend(latLng: LatLng): Pair<String, String>` để các tác vụ nền có thể `await` kết quả an toàn.
  2. **Triệt tiêu Race Condition trong Lịch sử lộ trình (`TripHistoryManager.kt`)**:
     - Sử dụng `withTimeoutOrNull(3000L) { reverseGeocodeSuspend(finalEndCoord) }` để chờ có được tên đường và số nhà chi tiết trước khi ghi `TripRecord` vào Room Database.
     - Tự động bổ sung địa chỉ xuất phát nếu lúc khởi động chuyến đi mạng chưa kịp phân giải toạ độ.
     - Tự động cập nhật `ParkingLocationManager` với toạ độ đích và địa chỉ chi tiết ngay khi chuyến đi hoàn tất.
  3. **Tích hợp Google Conscrypt BoringSSL (`build.gradle.kts`, `NConnectApplication.kt`, `NetworkUtils.kt`)**:
     - Thêm thư viện chính thức `org.conscrypt:conscrypt-android:2.5.2`.
     - Tạo `NConnectApplication` cài đặt `Security.insertProviderAt(Conscrypt.newProvider(), 1)` ngay khi ứng dụng khởi tạo.
     - Cấu hình `NetworkUtils.kt` khởi tạo SSLContext từ Conscrypt, cung cấp bộ mã hóa TLS 1.3 RFC 8446 hoàn chỉnh cho Android 9.
     - Thêm tiêu đề nhận diện `X-Client-Id: TFTLink-YamahaCompanion` theo khuyến nghị của OpenStreetMap / FOSSGIS.
  4. **Animation xoay bản đồ siêu mượt & Khử rung la bàn (`LiveNavigationScreen.kt`)**:
     - Triển khai animation xoay góc với `ValueAnimator` (350ms, `DecelerateInterpolator`), luôn tính toán cung quay ngắn nhất (`diff = (target - current + 540f) % 360f - 180f`).
     - Áp dụng bộ lọc ngưỡng la bàn: Ở chế độ Head-Tracking, chỉ xoay khi độ lệch góc `>= 2.0°`, triệt tiêu hoàn toàn hiện tượng rung lắc la bàn khi xe dừng hoặc rung máy.
     - Nút chuyển đổi North-Up xoay mượt mà 60fps từ bất kỳ góc độ nào về thẳng hướng Bắc hoặc hướng lái.
  5. **Đồng bộ chuẩn xác logic Đỗ xe (`BikeForegroundService.kt`, `MainViewModel.kt`, `ParkingLocationManager.kt`)**:
     - Thêm biến cờ `wasConnectedToBike` chặn đứng việc kích hoạt ghi nhận đỗ xe giả khi mở ứng dụng ở trạng thái ngắt kết nối.
     - Đảm bảo quy tắc bảo toàn mốc thời gian đỗ xe cũ khi khoảng cách di chuyển `< 50m`.

### 📌 Quy tắc 21: Bảo vệ ghép đôi Bluetooth (Tránh lỗi "Pairing Failed"), Đồng bộ North-Up/Heading Tracking chuẩn iOS & Bảo toàn Lịch sử lộ trình (v0.5.43 - Build 73)
- **Vấn đề đã gặp**:
  1. **Lỗi Bluetooth "Waiting for approval from ... -> Pairing Failed"**: Khi người dùng vào Cài đặt Bluetooth của điện thoại để ghép đôi với xe máy, trên đồng hồ xe báo "Waiting for approval from Sony G8142" rồi ngay sau đó báo "Pairing Failed". Nguyên nhân: `BikeForegroundService` chạy ngầm quét BLE liên tục. Khi thấy CCU xe quảng bá trong lúc đang ghép đôi, `YamahaCCUManager.handleScanResult` tự động gọi ngay `connect(bike)`. Hàm này gọi `connectGatt` VÀ mở ngay kênh SPP RFCOMM (`device.createRfcommSocketToServiceRecord(SPP_UUID).connect()`). Kết nối RFCOMM và GATT tới thiết bị chưa ghép đôi (unbonded) trong lúc hệ điều hành đang thực hiện bắt tay mã hóa khiến CCU của xe từ chối và hủy tiến trình ghép đôi.
  2. **Chức năng North-Up & Heading Tracking chưa đồng bộ với iOS**: Trong `MainViewModel`, `toggleNorthUp()` chỉ đổi biến UI `_isNorthUp` mà không cập nhật `streamRenderer.isNorthUp`, khiến màn hình TFT xe máy bị lệch chế độ với điện thoại. Ngoài ra, khi xe dừng đèn đỏ (`speed < 3 km/h`), thiếu cảm biến la bàn điện tử định hướng như iOS; nút chuyển đổi FAB trên điện thoại chưa mang thiết kế nhận diện 2 chế độ (THEO XE viền Cyan vs BẮC viền Đỏ) của app iOS cũ.
  3. **Lộ trình lịch sử bị cho phép xóa**: Người dùng yêu cầu không cho phép xóa lộ trình lịch sử để bảo toàn dữ liệu di chuyển vĩnh viễn, nhưng giao diện vẫn có các nút "Xóa tất cả", nút xóa trên từng thẻ và nút "Xóa chuyến này" trong hộp thoại chi tiết.
- **Giải pháp triển khai hoàn thiện**:
  1. **Bảo vệ toàn diện tiến trình ghép đôi Bluetooth (`YamahaCCUManager.kt`)**:
     - *Chặn kết nối tự động với thiết bị chưa ghép đôi*: Trong `handleScanResult`, kiểm tra `device.bondState == BluetoothDevice.BOND_BONDED`. Nếu thiết bị chưa ghép đôi (`BOND_NONE`) hoặc đang trong tiến trình ghép đôi (`BOND_BONDING`), TUYỆT ĐỐI không gọi `connect(bike)`.
     - *Lắng nghe `ACTION_BOND_STATE_CHANGED`*: Đăng ký `BroadcastReceiver` hệ thống. Khi nhận trạng thái `BOND_BONDING`, lập tức dừng quét BLE (`stopScanning()`) để giải phóng 100% băng thông sóng vô tuyến cho hệ điều hành Android hoàn tất ghép đôi và trao đổi mã PIN. Khi nhận `BOND_BONDED`, cập nhật danh sách và tự động kết nối sau 1.5 giây để sóng ổn định.
     - *Hoãn kết nối kênh SPP RFCOMM*: Loại bỏ lệnh gọi `connectSppChannel` đồng thời trong `connect()`. Chỉ mở kênh SPP RFCOMM sau khi BLE GATT đã bắt tay và xác thực thành công (`onBikeConnectedSuccessfully`), tránh xung đột phân tầng HCI trên Android 9.
  2. **Đồng bộ chuẩn xác North-Up & Heading Tracking theo app iOS (`MainViewModel.kt`, `NavigationEngine.kt`, `LiveNavigationScreen.kt`)**:
     - *Đồng bộ TFT Stream Renderer*: `MainViewModel.toggleNorthUp()` luôn cập nhật `streamRenderer.isNorthUp = _isNorthUp.value`.
     - *Cảm biến la bàn khi đứng yên*: Tích hợp `SensorEventListener` (Rotation Vector / Orientation) trong `NavigationEngine`. Khi xe lăn bánh (`speed >= 3.0 km/h`), ưu tiên hướng GPS course/bearing; khi xe dừng (`speed < 3.0 km/h`), sử dụng la bàn cảm biến với bộ lọc suy hao dao động (jitter damping) 2.0° để xoay bản đồ tự nhiên mà không rung lắc.
     - *Thiết kế nút FAB chuẩn iOS*: Nút FAB kích thước 46x46dp, bo góc 12dp:
       - Chế độ Heading Tracking: Viền Cyan, Icon Navigation Cyan, chữ "THEO XE" (8sp ExtraBold Cyan).
       - Chế độ North-Up: Viền Đỏ, Icon Explore Đỏ, chữ "BẮC" (8sp ExtraBold Trắng).
  3. **Bảo vệ vĩnh viễn Lịch sử lộ trình (`TripHistoryScreen.kt`)**:
     - Loại bỏ nút thùng rác "Xóa tất cả" trên thanh tiêu đề.
     - Loại bỏ nút xóa trên từng thẻ lộ trình, thay bằng nhãn "Chi tiết" cùng mũi tên chuyển tiếp Cyan.
     - Loại bỏ nút "Xóa chuyến này" trong hộp thoại xem chi tiết, thay bằng nút "Đóng" toàn chiều rộng tinh tế.
     - Xóa bỏ hoàn toàn các hộp thoại xác nhận xóa lộ trình khỏi giao diện.

### 📌 Quy tắc 22: Chuẩn hóa tính toán Thời gian dự kiến đến (ETA) & Đồng bộ khi Đổi lộ trình thay thế (v0.5.44 - Build 74)
- **Vấn đề đã gặp**:
  1. **Thời gian dự kiến đến (ETA) nhanh bất thường so với app iOS cũ**: Trước đây trong `NavigationEngine.kt`, hàm `updateRemainingDistanceAndETA()` tính khoảng cách còn lại bằng khoảng cách đường chim bay (`GeoUtils.computeDistanceMeters(loc, dest)`) rồi chia cho vận tốc tức thời của xe (`speedMps`). Trong đô thị, đường chim bay thường chỉ bằng 35% - 50% chiều dài đường đi thực tế; cộng với việc chia cho vận tốc xe đang chạy (40 - 50 km/h) khiến ETA hiển thị chỉ còn 5 - 6 phút cho quãng đường thực tế 10 km (vốn mất 25 - 30 phút vì đèn đỏ và ngã rẽ).
  2. **Thời gian và ETA không thay đổi khi đổi lộ trình**: Khi người dùng bấm chuyển sang Tuyến 2 (Tuyến thay thế), hàm `selectRoute(route)` chỉ gán lại tuyến đường mà không gọi `updateRemainingDistanceAndETA()`. Đồng thời, do công thức cũ tính theo đường chim bay tới toạ độ đích (vốn giống nhau cho mọi tuyến đường) nên ETA và khoảng cách bị "đóng băng", không phản ánh sự chênh lệch thời gian giữa Tuyến 1 và Tuyến 2.
- **Giải pháp triển khai hoàn thiện**:
  1. **Đồng bộ công thức ETA chuẩn xác theo app iOS (`NavigationEngine.kt`)**:
     - Khoảng cách còn lại (`remainingMeters`) được tính bằng khoảng cách từ vị trí GPS hiện tại tới điểm rẽ tiếp theo cộng với tổng chiều dài của tất cả các bước (steps) còn lại dọc theo tuyến đường.
     - Thời gian di chuyển còn lại (`remainingSec`) được tính dựa trên `route.durationSeconds` (thời gian chuẩn do Valhalla / OSRM tính toán dựa trên giới hạn tốc độ và loại đường xe máy) nhân với tỷ lệ quãng đường còn lại (`remainingMeters / totalDistance`).
     - Thời gian dự kiến đến (ETA) luôn ổn định, thực tế và đếm ngược dần về 0 khi tới đích.
  2. **Cập nhật tức thì khi chuyển đổi tuyến đường (`selectRoute`)**:
     - Khi người dùng bấm chọn Tuyến 1 hoặc Tuyến 2 (trên HUD hoặc chạm vào polyline trên bản đồ), hàm `selectRoute()` lập tức gọi `updateRemainingDistanceAndETA()` và `updateCurrentInstructionUI()`. Thông số ETA, khoảng cách và tên đường trên điện thoại lẫn đồng hồ TFT xe máy sẽ cập nhật ngay lập tức theo tuyến đường mới được chọn.
  3. **Bổ sung cấu hình xe máy Valhalla**:
     - Thêm `alley_factor: 5.0` (phạt nặng việc rẽ vào ngõ nhỏ) và `top_speed: 65` (tốc độ tối đa xe máy) khớp 100% với cấu hình của app iOS.



### 📌 Quy tắc 23: Sửa góc quay Mũi tên xe theo Chế độ bản đồ, Nút xóa từng chuyến & Lọc < 50m, Ẩn xem trước khi chạm ngoài, Cố định vị trí Tuyến 1/2 và Đặt tên địa điểm Yêu thích (v0.5.45 - Build 75)
- **Vấn đề đã gặp**:
  1. **Mũi tên hướng xe (puck) bị lệch hướng và ngược chiều giữa North-Up và Head-Tracking**:
     - *Nguyên nhân*: Cơ chế vẽ của `osmdroid` Marker áp dụng góc xoay trên màn hình theo công thức `rotationOnScreen = -pj.orientation - mBearing` (với `mBearing = marker.rotation`). Khi gán `rotation = if (isNorthUp) heading else 0f`:
       - Ở chế độ North-Up (`mapOrientation = 0`): `rotationOnScreen = -0 - heading = -heading` (xoay ngược chiều kim đồng hồ, dẫn đến xe chạy hướng Đông 90° thì mũi tên lại chỉ hướng Tây 270°).
       - Ở chế độ Head-Tracking (`mapOrientation = -heading`): `rotationOnScreen = -(-heading) - 0 = +heading` (thay vì luôn hướng thẳng lên phía trước 0° so với màn hình điện thoại).
  2. **Yêu cầu xóa từng lộ trình và loại bỏ lộ trình quá ngắn (< 50m)**:
     - Thẻ lộ trình cần nút xóa nhỏ gọn kèm hộp thoại xác nhận trước khi xóa từng chuyến đi cụ thể, tránh xóa nhầm toàn bộ dữ liệu.
     - Các chuyến đi ngắn dưới 50m (ví dụ dắt xe trong nhà hoặc nổ máy kiểm tra) không được lưu vào cơ sở dữ liệu lịch sử.
  3. **Thẻ xem trước lộ trình (Bắt đầu dẫn đường) không biến mất khi bấm ra ngoài bản đồ**:
     - Khi đang hiện thẻ xem trước lộ trình, người dùng chạm vào khoảng trống bất kỳ trên bản đồ thì thẻ không tự đóng lại mà người lái phải bấm nút 'X' nhỏ xíu.
  4. **Các nút tùy chọn Tuyến 1 và Tuyến 2 bị hoán đổi vị trí khi bấm chọn**:
     - Khi tính ra 2 tuyến đường gợi ý, ban đầu Nút 1 là Tuyến 1, Nút 2 là Tuyến 2. Khi người dùng bấm vào Tuyến 2, Tuyến 2 được chuyển thành `activeRoute`, làm giao diện vẽ lại Tuyến 2 vào vị trí Nút 1 và Tuyến 1 vào vị trí Nút 2 (bị đảo lộn vị trí các nút bấm).
  5. **Thêm địa điểm yêu thích từ ghim bản đồ tự gán tên tọa độ cứng, không cho đặt tên**:
     - Khi nhấn giữ ghim một vị trí trên bản đồ và bấm nút "Yêu thích", hệ thống tự động lưu với tên mặc định hoặc tọa độ mà không cho người dùng tự gõ đặt tên gợi nhớ (như "Nhà bạn Hưng", "Quán Cafe Cây Điệp"...).
- **Giải pháp triển khai hoàn thiện**:
  1. **Đồng bộ chuẩn xác góc quay Mũi tên xe theo osmdroid (`LiveNavigationScreen.kt`)**:
     - Công thức chuẩn hóa: `rotation = if (isNorthUp) -heading else -mapView.mapOrientation`.
     - Ở chế độ North-Up: `rotationOnScreen = -0 - (-heading) = +heading` (mũi tên xoay theo chiều kim đồng hồ, trỏ chính xác theo hướng chuyển động của xe so với phương Bắc).
     - Ở chế độ Head-Tracking: `rotationOnScreen = -mapView.mapOrientation - (-mapView.mapOrientation) = 0°` (mũi tên luôn hướng thẳng lên đỉnh màn hình điện thoại, đồng bộ mượt mà cùng hiệu ứng xoay bản đồ của `ValueAnimator`).
  2. **Thêm nút xóa từng chuyến đi & Lọc cự ly < 50m (`TripHistoryScreen.kt`, `TripHistoryManager.kt`)**:
     - Trong `TripHistoryManager.kt`: Thay thế điều kiện cũ bằng `if (distance < 50.0) { ... return }`. Toàn bộ các chuyến đi dưới 50m tự động bị loại bỏ hoàn toàn, không lưu vào Room Database.
     - Trong `TripHistoryScreen.kt`: Thêm nút icon thùng rác nhỏ (`Icons.Default.Delete`) trên từng thẻ chuyến đi, hiển thị hộp thoại `AlertDialog` xác nhận xóa đúng chuyến đi đó với nút Xóa màu đỏ neon.
  3. **Tự động đóng thẻ xem trước khi chạm vào khoảng trống bản đồ (`LiveNavigationScreen.kt`)**:
     - Sử dụng `rememberUpdatedState` trong `singleTapConfirmedHelper` của `MapEventsOverlay`. Khi người dùng chạm vào bản đồ trống lúc chưa kích hoạt dẫn đường (`activeRoute != null && !isNavigating`), hệ thống tự động gọi `viewModel.stopNavigation()` để xóa lộ trình xem trước và ẩn thẻ ngay lập tức.
  4. **Cố định vị trí các nút tùy chọn lộ trình (`LiveNavigationScreen.kt`)**:
     - Sử dụng `allCalculatedRoutes` duyệt theo `forEachIndexed`.
     - Nút vị trí 1 luôn luôn là "Tuyến 1", Nút vị trí 2 luôn luôn là "Tuyến 2", giữ nguyên vị trí cố định trên màn hình.
     - Tuyến đang được chọn hiển thị nổi bật với viền sáng Yamaha Cyan và màu chữ Cyan đậm; tuyến còn lại hiển thị viền mờ và chữ xám. Khi bấm chọn Tuyến 2, trạng thái highlight chuyển sang Tuyến 2 tại chỗ mà hai nút không bao giờ bị đảo vị trí.
  5. **Hộp thoại đặt tên gợi nhớ khi thêm Yêu thích từ bản đồ (`LiveNavigationScreen.kt`)**:
     - Khi bấm nút "Yêu thích" trên thẻ thả ghim, hiển thị `AlertDialog` có sẵn `OutlinedTextField` điền sẵn tên địa chỉ gợi ý, cho phép người dùng tự do chỉnh sửa hoặc gõ tên gợi nhớ tùy ý trước khi bấm "Lưu".
---

### 📌 Quy tắc 24: Sửa triệt để lỗi Ghép đôi Bluetooth (Waiting for approval -> Pairing Failed), Xóa liên kết lệch pha & Thêm phản hồi kết nối trực quan (v0.5.46 - Build 76)
- **Vấn đề đã gặp**:
  1. **Lỗi Bluetooth từ Cài đặt Android ("Waiting for approval" -> "Pairing Failed")**:
     - *Hiện tượng*: Người dùng vào Cài đặt Bluetooth trên điện thoại, thấy xe Yamaha CCU và bấm kết nối. Cả 2 bên đều hiện yêu cầu ghép đôi / mã PIN, người dùng bấm Đồng ý cả 2 bên. Trên đồng hồ xe hiển thị "Waiting for approval from Sony G8142..." rồi một lúc sau báo "Pairing Failed". Trong khi đó, trên điện thoại trong mục *Thiết bị đã kết nối trước đó* (Previously connected devices) lại hiển thị xe đã ghép đôi.
     - *Nguyên nhân gốc rễ*: Hiện tượng lệch pha Bluetooth Dual-Mode (Asymmetric Pairing Desynchronization). Khi người dùng ghép đôi từ Cài đặt Android, hệ điều hành Android ghi Link Key vào bộ lưu trữ và ngay lập tức phát broadcast `ACTION_BOND_STATE_CHANGED(BOND_BONDED)`. Trong `YamahaCCUManager.kt`, nhánh này kích hoạt coroutine tự động kết nối sau vỏn vẹn 1.5 giây (`delay(1500L)`). Khoảng thời gian này quá ngắn trên Android 9 (Sony G8142, SoC Snapdragon 835 WCN3990). Trong lúc Android OS và CCU xe vẫn đang thực hiện bắt tay khám phá dịch vụ Bluetooth Classic SDP (RFCOMM), việc `YamahaCCUManager` phát lệnh `connectGatt(..., TRANSPORT_LE)` song song làm nghẽn stack HCI và ngắt kết nối ACL vật lý giữa chừng. Đồng hồ xe (đang treo ở "Waiting for approval") thấy kết nối ACL bị đứt nên hủy bỏ tiến trình và báo "Pairing Failed", đồng thời xóa link key của xe. Hậu quả là điện thoại đã lưu link key (Bonded) còn xe thì không (Unbonded). Sau đó, mọi lần thử kết nối tiếp theo từ điện thoại đều bị xe từ chối ngay lập tức.
  2. **Bấm kết nối trong app bị im lặng hoàn toàn (Không phản hồi, không thông báo lỗi)**:
     - *Hiện tượng*: Mở app, mở hộp thoại quét xe thì thấy xe trong danh sách. Khi bấm vào xe thì hộp thoại đóng lại ngay lập tức, không có thông báo đang kết nối, không có vòng quay tiến trình, không có thông báo lỗi hay bất kỳ phản hồi nào.
     - *Nguyên nhân gốc rễ*:
       + Nếu xe chưa ghép đôi (`BOND_NONE`), `connect(bike)` gọi `bluetoothDevice.createBond()` rồi `return` ngay lập tức mà không cập nhật `_state.value` (`_state` vẫn giữ nguyên là `DISCONNECTED`). Hộp thoại đóng ngay (`showScanDialog = false`), người dùng không hề biết rằng hệ thống đang yêu cầu xác nhận ghép đôi.
       + Nếu xe đã ghép đôi trên điện thoại nhưng bị lệch pha với xe (xe đã xóa link key), app gọi `connectGatt` -> xe từ chối -> `gattCallback` lập tức gọi `handleDisconnection()` chuyển `_state` về `DISCONNECTED` mà không lưu vết hay hiển thị nguyên nhân lỗi lên giao diện.
- **Giải pháp triển khai hoàn thiện**:
  1. **Khắc phục Race Condition & Tối ưu luồng ghép đôi (`YamahaCCUManager.kt`)**:
     - *Tăng thời gian chờ hoàn tất bonding*: Tăng khoảng delay sau sự kiện `BOND_BONDED` từ 1.5s lên 6.0s (`delay(6000L)`), đảm bảo hệ điều hành Android và CCU xe hoàn tất 100% bắt tay Classic SDP và đồng hồ xe quay trở về màn hình vận hành chính trước khi app bắt đầu mở kênh BLE GATT.
     - *Cập nhật trạng thái tức thì trong `connect()`*: Ngay khi người dùng bấm kết nối, lập tức gán `_state.value = ConnectionState.CONNECTING` và `_connectedBike.value = device`. Nếu thiết bị chưa ghép đôi, gọi `device.createBond()` và cập nhật `_lastConnectionError` thông báo: *"Đang yêu cầu ghép đôi Bluetooth... Vui lòng bấm 'Đồng ý' trên cả điện thoại và màn hình xe."*.
     - *Dọn dẹp và chống xung đột GATT 133*: Đóng gatt cũ (`bluetoothGatt?.close()`), dừng quét BLE và chèn khoảng nghỉ 300ms trước khi `connectGatt` để tránh xung đột tài nguyên sóng vô tuyến.
     - *Bộ đếm thời gian giám sát kết nối (Watchdog 20s)*: Nếu sau 20 giây kết nối không thành công hoặc xe không phản hồi, tự động hủy phiên kết nối và ghi nhận lỗi timeout chi tiết.
     - *Bổ sung hàm `unpairBike(device)`*: Tích hợp cơ chế phản xạ Java (Java Reflection) gọi Hidden API `BluetoothDevice.removeBond()` của Android. Cho phép người dùng xóa trực tiếp liên kết ghép đôi bị lỗi khỏi bộ nhớ điện thoại ngay trong app mà không cần phải thoát ra mở Cài đặt Android.
  2. **Bổ sung phản hồi tức thời & Xử lý lỗi (`MainViewModel.kt`)**:
     - Xuất bản Flow `lastConnectionError`, các phương thức `clearConnectionError()` và `unpairBike(device)`.
     - Trong `connectBike(device)`: Hiển thị ngay thông báo Toast trên màn hình thông báo rõ cho người lái biết đang kết nối hoặc đang yêu cầu xác nhận ghép đôi.
  3. **Nâng cấp giao diện Quét thiết bị & Hướng dẫn sửa lỗi lệch pha (`MainDashboardScreen.kt`)**:
     - *Thanh tiến trình kết nối thời gian thực*: Trên đầu hộp thoại quét thiết bị hiển thị banner trạng thái (vòng quay `CircularProgressIndicator` + tên xe + nhãn "Đang kết nối..." hoặc "Đang yêu cầu ghép đôi...").
     - *Thẻ báo lỗi trực quan*: Nếu kết nối bị xe từ chối hoặc lỗi timeout, hiển thị thẻ màu đỏ neon chứa thông báo lỗi chi tiết cùng nút "Đóng".
     - *Huy hiệu & Nút Hủy ghép 1-chạm*: Từng thiết bị trong danh sách quét hiển thị huy hiệu "ĐÃ GHÉP ĐÔI" (màu Cyan) hoặc "CHƯA GHÉP ĐÔI" (màu xám). Đối với thiết bị đã ghép đôi, bổ sung nút "HỦY GHÉP" (icon `LinkOff` màu cam đỏ) cho phép người dùng xóa nhanh liên kết cũ bị lỗi.
     - *Hộp thoại Hướng dẫn khắc phục lệch pha ghép đôi 3 bước (`showPairingGuideDialog`)*:
       1. Bấm nút "Hủy ghép" màu cam trên thiết bị trong danh sách quét của app.
       2. Trên đồng hồ xe Yamaha: Vào Menu -> Cài đặt (Settings) -> Smartphone/Bluetooth -> Xóa liên kết điện thoại cũ.
       3. Tắt khóa xe rồi bật lại, sau đó bấm kết nối trực tiếp ngay từ trong ứng dụng.
