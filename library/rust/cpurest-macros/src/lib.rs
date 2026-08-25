//! Attribute macros `#[cpu_get]`, `#[cpu_post]`, `#[cpu_put]`, `#[cpu_delete]`.
//!
//! Applied to an `async fn` taking zero arguments or one `Json<T>` argument
//! and returning anything implementing `IntoCpuResponse` (in practice
//! `Json<R>` or `Result<Json<R>, CpuError>`), each macro leaves the
//! original function untouched and additionally emits a hidden wrapper +
//! an `inventory::submit!` registration, so `Server::mount()` can discover
//! every annotated handler in the binary without any manual route list.
//!
//! Deliberately does *not* try to parse the handler's return type: it emits
//! a generic call through the `IntoCpuResponse` trait and lets normal trait
//! resolution figure out which impl applies. That keeps this macro's job
//! down to "how many arguments, and what's the request type", which is the
//! only part that actually varies per handler.

use proc_macro::TokenStream;
use quote::{format_ident, quote};
use syn::{parse_macro_input, FnArg, GenericArgument, ItemFn, LitStr, PathArguments, Type};

fn expand(method_variant: &str, attr: TokenStream, item: TokenStream) -> TokenStream {
    let path = parse_macro_input!(attr as LitStr);
    let func = parse_macro_input!(item as ItemFn);

    let fn_name = &func.sig.ident;
    let wrapper_name = format_ident!("__cpurest_route_{}", fn_name);
    let method_ident = format_ident!("{}", method_variant);

    let inputs: Vec<_> = func.sig.inputs.iter().collect();

    let call_expr = match inputs.len() {
        0 => quote! { #fn_name() },
        1 => {
            let ty = match inputs[0] {
                FnArg::Typed(pt) => &*pt.ty,
                FnArg::Receiver(_) => {
                    return syn::Error::new_spanned(
                        &func.sig,
                        "cpurest handlers must be free functions, not methods (no `self`)",
                    )
                    .to_compile_error()
                    .into();
                }
            };
            match json_inner_type(ty) {
                Some(_inner) => quote! {
                    {
                        let __parsed = ::cpurest::__private::serde_json::from_slice(&__req_bytes);
                        match __parsed {
                            ::std::result::Result::Ok(__val) => #fn_name(::cpurest::Json(__val)).await,
                            ::std::result::Result::Err(__e) => {
                                return (400u16, ::cpurest::__private::encode_error(&__e.to_string()));
                            }
                        }
                    }
                },
                None => {
                    return syn::Error::new_spanned(
                        ty,
                        "cpurest handlers take zero arguments or exactly one `Json<T>` argument",
                    )
                    .to_compile_error()
                    .into();
                }
            }
        }
        _ => {
            return syn::Error::new_spanned(
                &func.sig,
                "cpurest handlers take zero arguments or exactly one `Json<T>` argument",
            )
            .to_compile_error()
            .into();
        }
    };

    let expanded = quote! {
        #func

        #[allow(non_snake_case)]
        fn #wrapper_name(
            __req_bytes: &[u8],
        ) -> ::std::pin::Pin<::std::boxed::Box<dyn ::std::future::Future<Output = (u16, ::std::vec::Vec<u8>)> + Send>> {
            let __req_bytes = __req_bytes.to_vec();
            ::std::boxed::Box::pin(async move {
                let __out = #call_expr;
                ::cpurest::__private::IntoCpuResponse::into_response(__out)
            })
        }

        ::cpurest::__private::cpurest_core::inventory::submit! {
            ::cpurest::__private::cpurest_core::route::RouteEntry {
                method: ::cpurest::__private::cpurest_core::Method::#method_ident,
                path: #path,
                handler: #wrapper_name,
            }
        }
    };

    expanded.into()
}

/// If `ty` is `Json<T>` (by last path segment name), returns `T`.
fn json_inner_type(ty: &Type) -> Option<&Type> {
    let Type::Path(type_path) = ty else { return None };
    let segment = type_path.path.segments.last()?;
    if segment.ident != "Json" {
        return None;
    }
    let PathArguments::AngleBracketed(args) = &segment.arguments else { return None };
    args.args.iter().find_map(|a| match a {
        GenericArgument::Type(t) => Some(t),
        _ => None,
    })
}

#[proc_macro_attribute]
pub fn cpu_get(attr: TokenStream, item: TokenStream) -> TokenStream {
    expand("Get", attr, item)
}

#[proc_macro_attribute]
pub fn cpu_post(attr: TokenStream, item: TokenStream) -> TokenStream {
    expand("Post", attr, item)
}

#[proc_macro_attribute]
pub fn cpu_put(attr: TokenStream, item: TokenStream) -> TokenStream {
    expand("Put", attr, item)
}

#[proc_macro_attribute]
pub fn cpu_delete(attr: TokenStream, item: TokenStream) -> TokenStream {
    expand("Delete", attr, item)
}
